package com.kbo.crawlerapi.api;

import com.kbo.crawlerapi.api.dto.AttendanceListResponse;
import com.kbo.crawlerapi.api.dto.AttendanceRequest;
import com.kbo.crawlerapi.service.AttendanceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @PostMapping
    public void upsert(@RequestBody AttendanceRequest request) {
        attendanceService.upsert(request.installationId(), request.gameId());
    }

    @DeleteMapping
    public void delete(@RequestBody AttendanceRequest request) {
        attendanceService.delete(request.installationId(), request.gameId());
    }

    @GetMapping
    public AttendanceListResponse list(@RequestParam String installationId) {
        return attendanceService.list(installationId);
    }
}
