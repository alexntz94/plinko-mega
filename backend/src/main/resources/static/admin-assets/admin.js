// Confirmation prompt for buttons with data-confirm (inline handlers are blocked by the CSP).
document.addEventListener('click', function (event) {
    var button = event.target.closest('[data-confirm]');
    if (button && !window.confirm(button.getAttribute('data-confirm'))) {
        event.preventDefault();
    }
});

// Live pages: every 5 seconds (or data-live-every seconds) the page fetches itself and swaps in the regions marked data-live, so new
// rounds and balances appear without reloading. Paused while the tab is hidden or someone is typing in a
// live region. The X-Live-Refresh header tells the server this is not user activity (idle sign-out still applies).
(function () {
    var regions = document.querySelectorAll('[data-live]');
    var status = document.getElementById('live-status');
    if (!regions.length) {
        return;
    }
    function stamp() {
        var now = new Date();
        status.hidden = false;
        status.textContent = 'Live · updated ' + now.toISOString().substring(11, 19) + ' UTC';
    }
    function refresh() {
        if (document.hidden) {
            return;
        }
        var active = document.activeElement;
        if (active && active.closest && active.closest('[data-live]') && /INPUT|SELECT|TEXTAREA/.test(active.tagName)) {
            return;
        }
        fetch(window.location.href, {credentials: 'same-origin', headers: {'X-Live-Refresh': '1'}, cache: 'no-store'})
            .then(function (response) {
                if (response.redirected || !response.ok) {
                    window.location.reload(); // signed out or an error: show it properly
                    return null;
                }
                return response.text();
            })
            .then(function (html) {
                if (html === null || html === undefined) {
                    return;
                }
                var fresh = new DOMParser().parseFromString(html, 'text/html');
                document.querySelectorAll('[data-live]').forEach(function (region) {
                    var replacement = fresh.querySelector('[data-live="' + region.getAttribute('data-live') + '"]');
                    if (replacement && replacement.innerHTML !== region.innerHTML) {
                        region.innerHTML = replacement.innerHTML;
                    }
                });
                stamp();
            })
            .catch(function () {
                status.hidden = false;
                status.textContent = 'Live updates paused: server not reachable';
            });
    }
    stamp();
    // pages that add up a lot (Finance) ask for a slower refresh
    var every = parseInt(regions[0].getAttribute('data-live-every') || '5', 10);
    setInterval(refresh, Math.max(5, every) * 1000);
})();

// Mock PAM: the "Game" dropdown of a wallet sets which theme its Open game / QR links launch.
document.addEventListener('change', function (event) {
    var select = event.target.closest('[data-game-select]');
    if (!select) {
        return;
    }
    var wallet = select.getAttribute('data-game-select');
    document.querySelectorAll('[data-game-link="' + wallet + '"]').forEach(function (link) {
        var url = new URL(link.href);
        if (select.value) {
            url.searchParams.set('theme', select.value);
        } else {
            url.searchParams.delete('theme');
        }
        link.href = url.toString();
    });
});

// Small screens: the sidebar opens as a sheet from the left; tapping outside closes it.
document.addEventListener('click', function (event) {
    if (event.target.closest('[data-nav-toggle]')) {
        document.body.classList.toggle('nav-open');
        return;
    }
    if (document.body.classList.contains('nav-open') && !event.target.closest('#sidebar')) {
        document.body.classList.remove('nav-open');
    }
});

// Filter bars: dropdowns, dates and toggles apply at once; the search box applies on Enter.
document.addEventListener('change', function (event) {
    var form = event.target.closest('form[data-autosubmit]');
    if (form && event.target.type !== 'search') {
        var page = form.querySelector('input[name=page]');
        if (page) { page.remove(); }
        form.requestSubmit ? form.requestSubmit() : form.submit();
    }
});

// Sign-in code screen: submit as soon as 6 digits are in (typed, pasted or filled in by the keyboard).
document.addEventListener('input', function (event) {
    var form = event.target.closest('form[data-code-form]');
    if (form && /^\d{6}$/.test(event.target.value) && !form.dataset.sent) {
        form.dataset.sent = '1'; //once: a second submit would count as a second wrong code
        form.requestSubmit ? form.requestSubmit() : form.submit();
    }
});

// A form sent twice by a double click (review 2.6): every action is safe to repeat on the server (a decided change
// says so, a retry sees the round already settled), but the button is held for a moment so it simply doesn't happen.
document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement) || (form.method || '').toLowerCase() !== 'post') {
        return;
    }
    form.querySelectorAll('button[type="submit"], button:not([type]), input[type="submit"]').forEach(function (button) {
        button.disabled = true;
        setTimeout(function () { button.disabled = false; }, 4000);
    });
});
