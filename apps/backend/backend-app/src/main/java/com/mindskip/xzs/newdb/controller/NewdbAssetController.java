package com.mindskip.xzs.newdb.controller;

import com.mindskip.xzs.newdb.config.NewdbProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 候选库本地题目资源只读端点（M1 修正块）：从 NEWDB_PAPER_ASSETS_DIR 私有目录
 * serve 已提取的题目配图副本。在 NewdbAuthFilter 认证内（原题资源不公开）；
 * 前端经带 token 的 fetch 转 blob 展示。文件名白名单 + 目录锚定，禁止路径穿越。
 */
@RestController
@RequestMapping("/api/newdb/asset")
@Profile("newdb")
public class NewdbAssetController {

    private static final Set<String> MEDIA_TYPES = Set.of("svg", "png", "jpg", "jpeg", "gif", "webp");

    private final NewdbProperties properties;

    public NewdbAssetController(NewdbProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/{filename}")
    public ResponseEntity<byte[]> serve(@PathVariable String filename) {
        String name = URLDecoder.decode(filename, StandardCharsets.UTF_8);
        if (!name.matches("[A-Za-z0-9._\\-]+") || name.contains("..")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        NewdbProperties.PaperImporter conf = properties.paperImporter();
        String assetsDir = conf == null ? null : conf.assetsDir();
        if (assetsDir == null || assetsDir.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Path root = Path.of(assetsDir).toAbsolutePath().normalize();
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            MediaType media = MEDIA_TYPES.contains(ext)
                    ? MediaType.parseMediaType(ext.equals("svg") ? "image/svg+xml" : "image/" + ext)
                    : MediaType.APPLICATION_OCTET_STREAM;
            if ("svg".equals(ext)) {
                // 不可信 SVG 最小安全边界（见 SvgGuard）：剥 script/on*/外链 href；
                // 纵深：CSP 禁一切外部加载，nosniff 防内容嗅探
                bytes = com.mindskip.xzs.newdb.importing.SvgGuard.clean(bytes);
            }
            return ResponseEntity.ok()
                    .contentType(media)
                    .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
