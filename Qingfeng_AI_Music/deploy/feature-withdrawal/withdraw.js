/* Temporary production withdrawal; local application code remains available. */
(() => {
  const agent = path => /^\/agent(?:\/|$)/.test(path);
  const hide = () => {
    document.querySelectorAll('a[href], .agent-nav-link').forEach(el => {
      if (el.classList.contains('agent-nav-link') || agent(new URL(el.getAttribute('href') || '/', location.origin).pathname)) el.hidden = true;
    });
    document.querySelectorAll('button').forEach(el => {
      if (/^(制作\s*(?:完整\s*)?MV|制作新的\s*MV|Create (?:a )?music video)$/i.test(el.textContent.trim())) el.hidden = true;
    });
  };
  for (const method of ['pushState', 'replaceState']) {
    const original = history[method];
    history[method] = function (state, title, url) {
      if (url != null && agent(new URL(url, location.href).pathname)) { location.assign('/create'); return; }
      return original.apply(this, arguments);
    };
  }
  window.addEventListener('popstate', () => { if (agent(location.pathname)) location.replace('/create'); });
  if (agent(location.pathname)) { location.replace('/create'); return; }
  const start = () => { hide(); new MutationObserver(hide).observe(document.body, {childList: true, subtree: true}); };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, {once: true}); else start();
})();
