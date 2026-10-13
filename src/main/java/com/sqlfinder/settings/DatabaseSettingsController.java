package com.sqlfinder.settings;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

/** 数据库设置接口 */
@RestController
@RequestMapping("/api/settings/database")
public class DatabaseSettingsController {
    private final DatabaseDiagnostics diagnostics;
    private final ReadonlyGrantGenerator generator;
    private final DatabaseSettingsService settings;
    public DatabaseSettingsController(DatabaseDiagnostics diagnostics,ReadonlyGrantGenerator generator,DatabaseSettingsService settings) {
        this.diagnostics=diagnostics; this.generator=generator; this.settings=settings;
    }
    @GetMapping public ResponseEntity<View> get(Principal principal) {
        var scope=diagnostics.configuration(principal.getName());
        return uncached(new View(scope.catalog(),scope.tables(),scope.accountTables(),settings.view()));
    }
    @PostMapping("/test") public ResponseEntity<DatabaseConnectionTester.Result> test(@RequestBody DatabaseSettingsRequest request) {
        return uncached(settings.test(request));
    }
    @PostMapping public ResponseEntity<DatabaseSettingsService.View> save(@RequestBody DatabaseSettingsRequest request) {
        return uncached(settings.save(request));
    }
    @PostMapping("/check") public ResponseEntity<DatabaseDiagnostics.Result> check(Principal principal) {
        return uncached(diagnostics.check(principal.getName()));
    }
    @PostMapping("/grants") public ResponseEntity<Map<String,String>> grants(@RequestBody ReadonlyGrantGenerator.Request request) {
        return uncached(Map.of("sql",generator.generate(request)));
    }
    private static <T> ResponseEntity<T> uncached(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
    public record View(String catalog,java.util.List<String> tables,java.util.List<String> accountTables,
                       DatabaseSettingsService.View connection) { }
}
