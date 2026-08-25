package com.kbo.crawlerapi.admin;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminScheduleImportController {

    private final AdminScheduleImportService scheduleImportService;

    public AdminScheduleImportController(AdminScheduleImportService scheduleImportService) {
        this.scheduleImportService = scheduleImportService;
    }

    @PostMapping("/admin/schedule/import")
    public String importSchedule(
            @RequestParam String fromMonth,
            @RequestParam String toMonth,
            RedirectAttributes redirectAttributes
    ) {
        try {
            var result = scheduleImportService.importRange(parseMonth(fromMonth), parseMonth(toMonth));
            redirectAttributes.addFlashAttribute("scheduleImportResult", result);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirectAttributes.addFlashAttribute("scheduleImportError", exception.getMessage());
        }
        return "redirect:/admin/dashboard";
    }

    private YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("월은 YYYY-MM 형식으로 입력해 주세요.");
        }
    }
}
