(() => {
    const storageKey = 'kbo-admin-appearance';
    const systemAppearance = window.matchMedia('(prefers-color-scheme: dark)');
    let preference = 'system';
    try {
        const stored = window.localStorage.getItem(storageKey);
        if (['system', 'light', 'dark'].includes(stored)) preference = stored;
    } catch (_) { /* Appearance still works when browser storage is unavailable. */ }

    function applyAppearance() {
        document.documentElement.dataset.theme = preference === 'system'
            ? (systemAppearance.matches ? 'dark' : 'light') : preference;
    }
    applyAppearance();
    systemAppearance.addEventListener('change', applyAppearance);

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('[data-appearance]').forEach(select => {
            select.value = preference;
            select.closest('.appearance-control').hidden = false;
            select.addEventListener('change', () => {
                preference = select.value;
                applyAppearance();
                try { window.localStorage.setItem(storageKey, preference); } catch (_) { }
            });
        });

        const pauseButton = document.querySelector('[data-refresh-toggle]');
        if (!pauseButton) return;
        pauseButton.hidden = false;
        let paused = false;
        pauseButton.addEventListener('click', () => {
            paused = !paused;
            pauseButton.setAttribute('aria-pressed', String(paused));
            pauseButton.textContent = paused ? '자동 갱신 재개' : '자동 갱신 일시정지';
            const status = document.querySelector('[data-refresh-status]');
            status.textContent = paused ? '자동 갱신 일시정지됨' : '15초마다 자동 갱신';
        });
        document.addEventListener('htmx:beforeRequest', event => {
            const region = event.detail.elt;
            if (region.id === 'dashboard-content' && (paused || region.contains(document.activeElement))) {
                event.preventDefault();
            }
        });
    });
})();
