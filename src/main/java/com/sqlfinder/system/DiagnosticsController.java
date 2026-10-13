package com.sqlfinder.system;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 启动诊断接口 */
@RestController
public class DiagnosticsController {
    private final StartupDiagnostics diagnostics;
    public DiagnosticsController(StartupDiagnostics diagnostics) { this.diagnostics = diagnostics; }

    @GetMapping("/api/system/diagnostics")
    public ResponseEntity<StartupDiagnostics.Report> check(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(diagnostics.check(authentication.getName()));
    }
}
