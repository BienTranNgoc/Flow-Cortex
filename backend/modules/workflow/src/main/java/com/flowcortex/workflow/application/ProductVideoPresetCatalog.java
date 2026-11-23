package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Built-in Product Video templates, MC portraits and 9:16 background presets served from classpath. */
@Component
public class ProductVideoPresetCatalog {

    public record TemplateSpec(
            String id,
            int version,
            String name,
            String summary,
            int estimatedCredits,
            int maxProductImages,
            List<String> requiredRoles,
            List<String> optionalRoles,
            boolean available) {
    }

    public record McPreset(
            String id,
            String label,
            String voiceGender,
            String imageUrl,
            String resourcePath) {
    }

    public record BackgroundPreset(
            String id,
            String label,
            String imageUrl,
            String resourcePath) {
    }

    private final boolean mcEnabled;

    public ProductVideoPresetCatalog() {
        this(false);
    }

    @Autowired
    public ProductVideoPresetCatalog(
            @Value("${flowcortex.product-video.mc-enabled:false}") boolean mcEnabled) {
        this.mcEnabled = mcEnabled;
    }

    private static List<TemplateSpec> buildTemplates(boolean mcEnabled) {
        return List.of(
                new TemplateSpec(
                        "showroom-v1",
                        1,
                        "Showroom sản phẩm",
                        "2 cảnh sản phẩm 5s + 5s (toàn cảnh và cận cảnh chi tiết), 480P 9:16",
                        800,
                        3,
                        List.of("product"),
                        List.of("background"),
                        true),
                new TemplateSpec(
                        "mc-v1",
                        1,
                        "MC giới thiệu sản phẩm",
                        "5s MC cầm sản phẩm nói trực diện + 5s cận cảnh sản phẩm, 480P 9:16",
                        1000,
                        3,
                        List.of("product", "mc"),
                        List.of("background"),
                        mcEnabled));
    }

    private static final List<McPreset> MC_PRESETS = List.of(
            new McPreset("mc-nu-tre", "MC Nữ trẻ trung", "female",
                    "/api/v1/product-videos/presets/mc/mc-nu-tre",
                    "product-video-presets/mc/mc-nu-tre.jpg"),
            new McPreset("mc-nu-thanh-lich", "MC Nữ thanh lịch", "female",
                    "/api/v1/product-videos/presets/mc/mc-nu-thanh-lich",
                    "product-video-presets/mc/mc-nu-thanh-lich.jpg"),
            new McPreset("mc-nam-nang-dong", "MC Nam năng động", "male",
                    "/api/v1/product-videos/presets/mc/mc-nam-nang-dong",
                    "product-video-presets/mc/mc-nam-nang-dong.jpg"));

    private static final List<BackgroundPreset> BACKGROUND_PRESETS = List.of(
            new BackgroundPreset("bg-studio-minimal", "Studio tối giản",
                    "/api/v1/product-videos/presets/backgrounds/bg-studio-minimal",
                    "product-video-presets/backgrounds/bg-studio-minimal.jpg"),
            new BackgroundPreset("bg-warm-wood", "Phòng nắng ấm",
                    "/api/v1/product-videos/presets/backgrounds/bg-warm-wood",
                    "product-video-presets/backgrounds/bg-warm-wood.jpg"),
            new BackgroundPreset("bg-luxury-dark", "Sang trọng nền tối",
                    "/api/v1/product-videos/presets/backgrounds/bg-luxury-dark",
                    "product-video-presets/backgrounds/bg-luxury-dark.jpg"),
            new BackgroundPreset("bg-cafe-modern", "Quán cà phê hiện đại",
                    "/api/v1/product-videos/presets/backgrounds/bg-cafe-modern",
                    "product-video-presets/backgrounds/bg-cafe-modern.jpg"));

    private static final Map<String, McPreset> MC_BY_ID = MC_PRESETS.stream()
            .collect(Collectors.toUnmodifiableMap(McPreset::id, Function.identity()));

    private static final Map<String, BackgroundPreset> BG_BY_ID = BACKGROUND_PRESETS.stream()
            .collect(Collectors.toUnmodifiableMap(BackgroundPreset::id, Function.identity()));

    public List<TemplateSpec> templates() {
        return buildTemplates(mcEnabled);
    }

    public List<McPreset> mcPresets() {
        return MC_PRESETS;
    }

    public List<BackgroundPreset> backgroundPresets() {
        return BACKGROUND_PRESETS;
    }

    public boolean isValidMcPreset(String id) {
        return id != null && MC_BY_ID.containsKey(id);
    }

    public boolean isValidBackgroundPreset(String id) {
        return id != null && BG_BY_ID.containsKey(id);
    }

    public byte[] loadPresetImage(String category, String id) {
        String resourcePath = switch (category) {
            case "mc" -> MC_BY_ID.containsKey(id) ? MC_BY_ID.get(id).resourcePath() : null;
            case "backgrounds" -> BG_BY_ID.containsKey(id) ? BG_BY_ID.get(id).resourcePath() : null;
            default -> null;
        };
        if (resourcePath == null) {
            throw new NotFoundException(new LocalizedMessage("workflow.productVideo.presetNotFound"));
        }
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new NotFoundException(new LocalizedMessage("workflow.productVideo.presetNotFound"));
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read preset image " + resourcePath, e);
        }
    }
}
