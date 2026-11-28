import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';

const ProductVideoTemplateSpecSchema = z.object({
  id: z.enum(['showroom-v1', 'mc-v1']),
  version: z.number().int().positive(),
  name: z.string().min(1),
  summary: z.string(),
  estimatedCredits: z.number().nonnegative().nullable().optional(),
  maxProductImages: z.number().int().positive(),
  requiredRoles: z.array(z.string()),
  optionalRoles: z.array(z.string()),
  available: z.boolean().default(true),
});

const McPresetOptionSchema = z.object({
  id: z.string().min(1),
  label: z.string().min(1),
  voiceGender: z.enum(['male', 'female']),
  imageUrl: z.string().min(1),
});

const BackgroundPresetOptionSchema = z.object({
  id: z.string().min(1),
  label: z.string().min(1),
  imageUrl: z.string().min(1),
});

export const ProductVideoTemplatesCatalogSchema = z.object({
  templates: z.array(ProductVideoTemplateSpecSchema).min(1),
  mcPresets: z.array(McPresetOptionSchema),
  backgroundPresets: z.array(BackgroundPresetOptionSchema),
});

export type ProductVideoTemplateId = 'showroom-v1' | 'mc-v1';
export type VoiceGender = 'male' | 'female';
export type ProductVideoTemplateSpec = z.infer<typeof ProductVideoTemplateSpecSchema>;
export type McPresetOption = z.infer<typeof McPresetOptionSchema>;
export type BackgroundPresetOption = z.infer<typeof BackgroundPresetOptionSchema>;
export type ProductVideoTemplatesCatalog = z.infer<typeof ProductVideoTemplatesCatalogSchema>;

export const FALLBACK_PRODUCT_VIDEO_CATALOG: ProductVideoTemplatesCatalog = {
  templates: [
    {
      id: 'showroom-v1',
      version: 1,
      name: 'Showroom sản phẩm',
      summary: '2 cảnh sản phẩm 5s + 5s (toàn cảnh và cận cảnh), 480P 9:16',
      estimatedCredits: 900,
      maxProductImages: 3,
      requiredRoles: ['product'],
      optionalRoles: ['background'],
      available: true,
    },
    {
      id: 'mc-v1',
      version: 1,
      name: 'MC giới thiệu sản phẩm',
      summary: '5s MC cầm sản phẩm nói trực diện + 5s cận cảnh SP, 480P 9:16',
      estimatedCredits: 1000,
      maxProductImages: 3,
      requiredRoles: ['product', 'mc'],
      optionalRoles: ['background'],
      available: false,
    },
  ],
  mcPresets: [
    {
      id: 'mc-nu-tre',
      label: 'MC Nữ trẻ trung',
      voiceGender: 'female',
      imageUrl: '/api/v1/product-videos/presets/mc/mc-nu-tre',
    },
    {
      id: 'mc-nu-thanh-lich',
      label: 'MC Nữ thanh lịch',
      voiceGender: 'female',
      imageUrl: '/api/v1/product-videos/presets/mc/mc-nu-thanh-lich',
    },
    {
      id: 'mc-nam-nang-dong',
      label: 'MC Nam năng động',
      voiceGender: 'male',
      imageUrl: '/api/v1/product-videos/presets/mc/mc-nam-nang-dong',
    },
  ],
  backgroundPresets: [
    {
      id: 'bg-studio-minimal',
      label: 'Studio tối giản',
      imageUrl: '/api/v1/product-videos/presets/backgrounds/bg-studio-minimal',
    },
    {
      id: 'bg-warm-wood',
      label: 'Phòng nắng ấm',
      imageUrl: '/api/v1/product-videos/presets/backgrounds/bg-warm-wood',
    },
    {
      id: 'bg-luxury-dark',
      label: 'Sang trọng nền tối',
      imageUrl: '/api/v1/product-videos/presets/backgrounds/bg-luxury-dark',
    },
    {
      id: 'bg-cafe-modern',
      label: 'Quán cà phê hiện đại',
      imageUrl: '/api/v1/product-videos/presets/backgrounds/bg-cafe-modern',
    },
  ],
};

async function fetchProductVideoTemplates(): Promise<ProductVideoTemplatesCatalog> {
  try {
    const response = await fetch('/api/v1/product-videos/templates', {
      credentials: 'include',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) {
      return FALLBACK_PRODUCT_VIDEO_CATALOG;
    }
    const parsed = ProductVideoTemplatesCatalogSchema.safeParse(await response.json());
    return parsed.success ? parsed.data : FALLBACK_PRODUCT_VIDEO_CATALOG;
  } catch {
    return FALLBACK_PRODUCT_VIDEO_CATALOG;
  }
}

export function useProductVideoTemplatesQuery() {
  return useQuery({
    queryKey: ['product-video-templates'],
    queryFn: fetchProductVideoTemplates,
    initialData: FALLBACK_PRODUCT_VIDEO_CATALOG,
    initialDataUpdatedAt: 0,
    staleTime: 5 * 60 * 1000,
  });
}
