package com.querylens.infra.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/cache")
public class CacheRefreshController {

    private final RefreshCacheApiService refreshCacheApiService;

    @Autowired
    public CacheRefreshController(RefreshCacheApiService refreshCacheApiService) {
        this.refreshCacheApiService = refreshCacheApiService;
    }

    @PostMapping("/refresh/{type}")
    public ResponseEntity<Void> refresh(@PathVariable LocalCacheType type) {
        refreshCacheApiService.refresh(type);
        return ResponseEntity.noContent().build();
    }
}
