// Host mode only: Piik's page and Piik's engine run on the same phone and
// talk over WebRTC. Android WebView hides the phone's addresses in ICE
// candidates behind random ".local" names that the engine can't resolve
// (no multicast on mobile data, and WebView ignores the usual unlock).
// This rewrites each hidden candidate into real-address copies, using the
// addresses the app passes in. Wrong guesses simply fail their checks.
(function () {
  if (window.__piikIceFix) return;
  window.__piikIceFix = true;
  var ADDRS = __PIIK_ADDRESSES__;
  if (!window.RTCPeerConnection || !ADDRS.length) return;
  var MDNS = /^([0-9a-f-]+\.local)$/i;

  function expandLine(line) {
    // candidate:<foundation> <component> <transport> <priority> <address> <port> typ ...
    var parts = line.split(' ');
    if (parts.length < 8 || !MDNS.test(parts[4])) return [line];
    return ADDRS.map(function (ip, i) {
      var p = parts.slice();
      p[4] = ip;
      p[0] = p[0] + 'p' + i; // distinct foundation per copy
      return p.join(' ');
    });
  }

  function fixSdp(sdp) {
    if (!sdp || sdp.indexOf('.local') < 0) return sdp;
    var out = [];
    sdp.split('\r\n').forEach(function (line) {
      if (line.indexOf('a=candidate:') === 0) {
        expandLine(line.slice(2)).forEach(function (l) { out.push('a=' + l); });
      } else {
        out.push(line);
      }
    });
    return out.join('\r\n');
  }

  function fixDesc(desc) {
    if (!desc || !desc.sdp || desc.sdp.indexOf('.local') < 0) return desc;
    return new RTCSessionDescription({ type: desc.type, sdp: fixSdp(desc.sdp) });
  }

  var P = RTCPeerConnection.prototype;
  ['localDescription', 'currentLocalDescription', 'pendingLocalDescription'].forEach(function (name) {
    var d = Object.getOwnPropertyDescriptor(P, name);
    if (!d || !d.get) return;
    Object.defineProperty(P, name, {
      configurable: true, enumerable: d.enumerable,
      get: function () { return fixDesc(d.get.call(this)); },
    });
  });

  // One hidden candidate event becomes one event per real address.
  function expandEvent(ev, deliver) {
    var c = ev.candidate;
    if (!c || !c.candidate || c.candidate.indexOf('.local') < 0) return deliver(ev);
    expandLine(c.candidate).forEach(function (line) {
      var cand = new RTCIceCandidate({
        candidate: line, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex,
        usernameFragment: c.usernameFragment,
      });
      var copy = new RTCPeerConnectionIceEvent('icecandidate', { candidate: cand });
      deliver(copy);
    });
  }

  var wrapped = new WeakMap();
  function wrap(fn) {
    if (typeof fn !== 'function') return fn;
    var w = wrapped.get(fn);
    if (!w) {
      w = function (ev) {
        var self = this;
        expandEvent(ev, function (e) { fn.call(self, e); });
      };
      wrapped.set(fn, w);
    }
    return w;
  }

  var add = P.addEventListener, remove = P.removeEventListener;
  P.addEventListener = function (type, fn, opts) {
    return add.call(this, type, type === 'icecandidate' ? wrap(fn) : fn, opts);
  };
  P.removeEventListener = function (type, fn, opts) {
    return remove.call(this, type, type === 'icecandidate' ? wrap(fn) : fn, opts);
  };
  var on = Object.getOwnPropertyDescriptor(P, 'onicecandidate');
  if (on && on.set) {
    var originals = new WeakMap();
    Object.defineProperty(P, 'onicecandidate', {
      configurable: true, enumerable: on.enumerable,
      get: function () { return originals.get(this) || null; },
      set: function (fn) { originals.set(this, fn); on.set.call(this, wrap(fn)); },
    });
  }
})();
