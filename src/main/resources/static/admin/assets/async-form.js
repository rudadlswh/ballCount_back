(() => {
    const formSelector = "[data-async-form]";

    function requestForm(event) {
        const source = event.detail?.elt instanceof Element ? event.detail.elt : event.target;
        return source instanceof Element ? source.closest(formSelector) : null;
    }

    function panelFor(form) {
        const selector = form.dataset.asyncPanel;
        return selector ? document.querySelector(selector) : form.closest("[data-async-panel]");
    }

    function setLoading(form, loading) {
        const panel = panelFor(form);
        form.classList.toggle("is-loading", loading);
        form.setAttribute("aria-busy", String(loading));
        panel?.setAttribute("aria-busy", String(loading));

        const submitButton = form.querySelector("button[type='submit']");
        if (submitButton) {
            submitButton.disabled = loading;
        }

        const content = panel?.querySelector("[data-async-content]");
        if (content) {
            content.toggleAttribute("inert", loading);
            content.setAttribute("aria-hidden", String(loading));
        }
    }

    function clearClientError(form) {
        const error = panelFor(form)?.querySelector("[data-async-client-error]");
        if (error) {
            error.hidden = true;
        }
    }

    function showClientError(form) {
        setLoading(form, false);
        const error = panelFor(form)?.querySelector("[data-async-client-error]");
        if (error) {
            error.hidden = false;
            error.focus();
        }
    }

    document.addEventListener("htmx:beforeRequest", (event) => {
        const form = requestForm(event);
        if (!form) return;
        clearClientError(form);
        setLoading(form, true);
    });

    document.addEventListener("htmx:afterRequest", (event) => {
        const form = requestForm(event);
        if (form) setLoading(form, false);
    });

    ["htmx:sendError", "htmx:responseError", "htmx:timeout"].forEach((eventName) => {
        document.addEventListener(eventName, (event) => {
            const form = requestForm(event);
            if (form) showClientError(form);
        });
    });

    document.addEventListener("htmx:afterSwap", (event) => {
        const target = event.target;
        const panel = target instanceof Element
            ? (target.matches("[data-async-panel]") ? target : target.closest("[data-async-panel]"))
            : null;
        panel?.querySelector("[data-async-feedback]")?.focus();
    });
})();
