package com.kbo.crawlerapi.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

final class SecurityRequestPath {
    private SecurityRequestPath() { }

    static String withinApplication(HttpServletRequest request) {
        // Servlet paths have already been decoded and normalized by the container.
        // Decoding them again could turn a literal percent sequence into a different route.
        String path = request.getServletPath();
        if (request.getPathInfo() != null) {
            path += request.getPathInfo();
        }
        if (path.isEmpty()) {
            return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        }
        return UrlPathHelper.defaultInstance.removeSemicolonContent(path);
    }
}
