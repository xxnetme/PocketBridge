/* Per-page thumbnail credentials. No access codes or tokens are persisted here. */
(function (global) {
  'use strict';

  function create(request) {
    let token = '', expires = 0, retryAt = 0, refreshAt = 0, pending = null, revision = 0, issued = 0;
    const now = () => performance.now();

    function acquire(force = false) {
      if (pending) return pending;
      const start = now();
      if (!force && token && expires > start) return Promise.resolve(token);
      if (start < retryAt || (force && start < refreshAt)) {
        return Promise.reject(new Error('Thumbnail session retry delayed'));
      }
      // <img> errors do not reveal the HTTP status. At most one refresh for a burst
      // of broken images, even if the errors arrive after the first refresh completed.
      if (force) refreshAt = start + 30_000;
      const version = revision;
      pending = request(token).then(data => {
        if (version !== revision) throw new Error('Thumbnail session changed');
        token = data.token;
        issued++;
        expires = now() + data.ttl;
        return token;
      }).catch(error => {
        if (version === revision) {
          const seconds = error.status === 429 ? Number(error.headers?.get('Retry-After')) : 0;
          retryAt = now() + (seconds > 0 ? 1000 * seconds : 5000);
        }
        throw error;
      }).finally(() => {
        if (version === revision) pending = null;
      });
      return pending;
    }

    function append(container, item, fallback) {
      if (!token || expires <= now()) { fallback(); return; }
      const version = revision, usedToken = token, usedIssue = issued;
      const url = current => '/thumb?token=' + encodeURIComponent(current)
        + '&path=' + encodeURIComponent(item.path) + '&w=320&v=' + encodeURIComponent(String(item.modified || 0));
      const img = document.createElement('img');
      container.classList.add('is-loading');
      img.alt = item.name;
      img.loading = 'lazy';
      img.onload = () => { img.classList.add('is-ready'); container.classList.remove('is-loading'); };
      let retried = false;
      img.onerror = async () => {
        if (!retried && img.isConnected && version === revision) {
          retried = true;
          try {
            const fresh = token && usedIssue !== issued && expires > now() ? token : await acquire(true);
            if (img.isConnected && version === revision) img.src = url(fresh) + '&retry=1';
            return;
          } catch (_) { /* The other file actions do not need a thumbnail session. */ }
        }
        if (img.isConnected && version === revision) {
          img.remove();
          fallback();
        }
      };
      img.src = url(usedToken);
      container.append(img);
    }

    function reset() {
      revision++;
      token = '';
      expires = retryAt = refreshAt = 0;
      pending = null;
    }

    return { ensure: acquire, append, reset };
  }

  global.PocketBridgeThumbSession = { create };
})(window);
