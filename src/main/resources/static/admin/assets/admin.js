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

        document.querySelectorAll('.schedule-import-form').forEach(form => {
            const button = form.querySelector('button[type="submit"]');
            const spinner = form.querySelector('[data-schedule-import-spinner]');
            const label = form.querySelector('[data-schedule-import-label]');
            const status = form.querySelector('[data-schedule-import-status]');
            const setLoading = loading => {
                button.disabled = loading;
                form.setAttribute('aria-busy', String(loading));
                spinner.hidden = !loading;
                status.hidden = !loading;
                label.textContent = loading ? '수집 중…' : '일정 수집 실행';
            };
            form.addEventListener('submit', event => {
                if (event.defaultPrevented) return;
                if (button.disabled) {
                    event.preventDefault();
                    return;
                }
                setLoading(true);
            });
            window.addEventListener('pageshow', () => setLoading(false));
        });

        const pauseButton = document.querySelector('[data-refresh-toggle]');
        if (!pauseButton) return;
        const duration = document.querySelector('[data-refresh-duration]');
        const status = document.querySelector('[data-refresh-status]');
        document.querySelector('[data-refresh-duration-control]').hidden = false;
        pauseButton.hidden = false;
        let paused = false;
        let pausedUntil = null;
        let resumeTimer;

        function setPaused(value) {
            clearTimeout(resumeTimer);
            paused = value;
            const minutes = Number(duration.value);
            pausedUntil = paused && minutes > 0 ? Date.now() + minutes * 60_000 : null;
            duration.disabled = paused;
            pauseButton.setAttribute('aria-pressed', String(paused));
            pauseButton.textContent = paused ? '자동 갱신 재개' : '자동 갱신 일시정지';
            status.textContent = pausedUntil
                ? `${new Date(pausedUntil).toLocaleTimeString('ko-KR')}까지 일시정지`
                : (paused ? '수동 재개까지 일시정지됨' : '15초마다 자동 갱신');
            if (pausedUntil) resumeTimer = setTimeout(() => setPaused(false), pausedUntil - Date.now());
        }

        function resumeIfExpired() {
            if (pausedUntil && Date.now() >= pausedUntil) setPaused(false);
        }

        pauseButton.addEventListener('click', () => setPaused(!paused));
        document.addEventListener('visibilitychange', resumeIfExpired);
        document.addEventListener('htmx:beforeRequest', event => {
            resumeIfExpired();
            const region = event.detail.elt;
            if (region.id === 'dashboard-content' && (paused || region.contains(document.activeElement))) {
                event.preventDefault();
            }
        });
    });
})();
