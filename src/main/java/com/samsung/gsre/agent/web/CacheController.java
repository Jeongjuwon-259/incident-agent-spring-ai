package com.samsung.gsre.agent.web;

import com.samsung.gsre.agent.rag.VerifiedSqlCacheService;
import com.samsung.gsre.agent.web.dto.CacheEntryRequest;
import com.samsung.gsre.agent.web.dto.CacheEntryView;
import com.samsung.gsre.agent.web.mapper.WebMapper;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 검증 SQL 캐시 API. 자동 등록은 없고, 검증자(verifiedBy)가 있는 등록만 허용한다. */
@RestController
@RequestMapping("/api/v1/cache")
@RequiredArgsConstructor
public class CacheController {

    private final VerifiedSqlCacheService cacheService;

    @GetMapping
    public List<CacheEntryView> entries() {
        return cacheService.entries().stream().map(WebMapper::toCacheView).toList();
    }

    @PostMapping
    public ResponseEntity<CacheEntryView> register(@Valid @RequestBody CacheEntryRequest request) {
        var registered = cacheService.register(request.question(), request.sql(), request.parameters(),
                request.verifiedBy(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CacheEntryView(
                registered.id(), registered.question(), registered.sql(), registered.tables(),
                registered.defaultParams(), registered.verifiedBy(), registered.notes(), 0,
                java.time.Instant.now()));
    }
}
