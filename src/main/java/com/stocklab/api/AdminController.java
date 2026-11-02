package com.stocklab.api;

import com.stocklab.admin.AdminService;
import com.stocklab.admin.ResetRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 로컬 실험용 관리 API. 측정 중에는 호출하지 않는다. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody ResetRequest request) {
        adminService.reset(request.productCount(), request.initialStock());
        return Map.of("status", "OK", "productCount", request.productCount(), "initialStock", request.initialStock());
    }

    @GetMapping("/result")
    public AdminService.AdminResult result() {
        return adminService.result();
    }

    @ExceptionHandler(ResetRejectedException.class)
    public ResponseEntity<Map<String, String>> rejected(ResetRejectedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "REJECTED", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("status", "BAD_REQUEST", "message", e.getMessage()));
    }

    public record ResetRequest(int productCount, int initialStock) {
    }
}
