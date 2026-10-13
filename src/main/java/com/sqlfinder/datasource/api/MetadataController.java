package com.sqlfinder.datasource.api;

import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.datasource.model.TableSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.security.Principal;

/** 表结构查询接口 */

@Profile("local-metadata")
@RestController
@RequestMapping("/api/datasource")
public class MetadataController {

    private final UserMetadataService metadataService;

    @Autowired
    public MetadataController(UserMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/schema")
    public List<TableSchema> schema(Principal principal) {
        return metadataService.getAuthorizedSchemas(principal.getName());
    }
}
