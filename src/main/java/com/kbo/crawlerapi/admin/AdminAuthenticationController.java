package com.kbo.crawlerapi.admin;

import com.kbo.crawlerapi.config.AdminApiKeyFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminAuthenticationController {

    private final AdminAuthenticationService authenticationService;

    public AdminAuthenticationController(AdminAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @GetMapping("/admin/login")
    public String login(HttpServletRequest request, Model model) {
        if (isAuthenticated(request.getSession(false))) {
            return "redirect:/admin/dashboard";
        }
        model.addAttribute("configurationMissing", !authenticationService.isConfigured());
        return "admin/login";
    }

    @PostMapping("/admin/login")
    public String authenticate(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String password,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) {
        if (!authenticationService.authenticate(username, password)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            model.addAttribute("loginError", "아이디 또는 비밀번호를 확인해 주세요.");
            model.addAttribute("configurationMissing", !authenticationService.isConfigured());
            model.addAttribute("username", username == null ? "" : username);
            return "admin/login";
        }

        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(AdminApiKeyFilter.ADMIN_SESSION_ATTRIBUTE, Boolean.TRUE);
        session.setMaxInactiveInterval(authenticationService.sessionTimeoutSeconds());
        return "redirect:/admin/dashboard";
    }

    @PostMapping("/admin/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/admin/login";
    }

    private boolean isAuthenticated(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute(AdminApiKeyFilter.ADMIN_SESSION_ATTRIBUTE));
    }
}
