const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');

const script = readFileSync(new URL('../../main/resources/static/admin/assets/admin.js', `file://${__filename}`), 'utf8');

function dashboard(minutes = '5') {
    let now = 0;
    let timerId = 0;
    const timers = new Map();
    const listeners = {};
    const button = {
        addEventListener: (_, callback) => { button.click = callback; },
        setAttribute: (name, value) => { button[name] = value; }
    };
    const duration = { value: minutes };
    const status = {};
    const controls = {};
    const elements = {
        '[data-refresh-toggle]': button,
        '[data-refresh-duration]': duration,
        '[data-refresh-status]': status,
        '[data-refresh-duration-control]': controls
    };
    runInNewContext(script, {
        window: { matchMedia: () => ({ addEventListener() {} }), localStorage: { getItem() {} } },
        document: {
            documentElement: { dataset: {} },
            querySelectorAll: () => [],
            querySelector: selector => elements[selector],
            addEventListener: (name, callback) => { listeners[name] = callback; }
        },
        Date: class extends Date { static now() { return now; } },
        setTimeout: (callback, delay) => { timers.set(++timerId, { callback, deadline: now + delay }); return timerId; },
        clearTimeout: id => timers.delete(id)
    });
    listeners.DOMContentLoaded();
    return {
        button, duration, status, controls, timers,
        advance(milliseconds, runTimers = true) {
            now += milliseconds;
            if (runTimers) for (const timer of [...timers.values()]) {
                if (timer.deadline <= now) timer.callback();
            }
        },
        visibilityChange: () => listeners.visibilitychange(),
        request(focused = false, id = 'dashboard-content') {
            let blocked = false;
            listeners['htmx:beforeRequest']({
                detail: { elt: { id, contains: () => focused } },
                preventDefault: () => { blocked = true; }
            });
            return blocked;
        }
    };
}

test('each timed pause blocks polling until the selected duration expires', () => {
    for (const minutes of ['1', '5', '10', '30', '60']) {
        const page = dashboard(minutes);
        assert.equal(page.controls.hidden, false);
        assert.equal(page.request(), false);
        page.button.click();
        assert.equal(page.duration.disabled, true);
        assert.equal(page.button['aria-pressed'], 'true');
        assert.match(page.status.textContent, /까지 일시정지/);
        page.advance(Number(minutes) * 60_000 - 1);
        assert.equal(page.request(), true);
        assert.equal(page.request(false, 'other-content'), false);
        page.advance(1);
        assert.equal(page.request(), false);
        assert.equal(page.duration.disabled, false);
        assert.equal(page.button['aria-pressed'], 'false');
    }
});

test('manual resume cancels the old timer before another pause', () => {
    const page = dashboard();
    page.button.click();
    page.button.click();
    assert.equal(page.timers.size, 0);
    page.duration.value = '10';
    page.button.click();
    page.advance(5 * 60_000);
    assert.equal(page.request(), true);
    page.advance(5 * 60_000);
    assert.equal(page.request(), false);
});

test('manual-only pause has no expiry', () => {
    const page = dashboard('0');
    page.button.click();
    assert.equal(page.timers.size, 0);
    page.advance(24 * 60 * 60_000);
    assert.equal(page.request(), true);
    page.button.click();
    assert.equal(page.request(), false);
});

test('expiry survives delayed background timers and preserves focused content', () => {
    const page = dashboard('1');
    page.button.click();
    page.advance(60_000, false);
    assert.equal(page.request(true), true);
    assert.equal(page.button['aria-pressed'], 'false');
    assert.equal(page.request(), false);
    page.button.click();
    page.advance(60_000, false);
    page.visibilityChange();
    assert.equal(page.status.textContent, '15초마다 자동 갱신');
});
