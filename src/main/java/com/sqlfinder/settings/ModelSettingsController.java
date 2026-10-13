package com.sqlfinder.settings;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 模型设置接口 */
@RestController
@RequestMapping("/api/settings")
public class ModelSettingsController {
    private final ModelSettingsService service;
    private final ModelConnectionTester tester;
    public ModelSettingsController(ModelSettingsService service, ModelConnectionTester tester) {
        this.service = service;
        this.tester = tester;
    }
    @GetMapping("/access")
    public ResponseEntity<Map<String, Boolean>> access(Authentication authentication) {
        return uncached(Map.of("canManageSettings", authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SETTINGS_ADMIN"))));
    }
    @GetMapping("/model")
    public ResponseEntity<ModelSettingsService.View> get() { return uncached(service.view()); }
    @PostMapping("/model")
    public ResponseEntity<ModelSettingsService.View> save(@RequestBody ModelSettingsRequest request) {
        return uncached(service.save(request));
    }
    @PostMapping("/model/test")
    public ResponseEntity<ModelConnectionTester.Result> test(@RequestBody ModelSettingsRequest request) {
        return uncached(tester.test(service.candidate(request)));
    }
    private static <T> ResponseEntity<T> uncached(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
