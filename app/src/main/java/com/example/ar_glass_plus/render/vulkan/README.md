# Vulkan backend (design only — DO NOT IMPLEMENT)

OpenGL ES is the primary rendering backend. This directory intentionally has
no implementation.

## When Vulkan becomes justified (all must hold, not just one)

- OpenGL profiling shows driver CPU overhead / sync stalls / draw-call cost is
  the bottleneck (Render GPU > ~3ms or pipeline misses 16.67ms budget).
- Feature need: multi-layer texture composition, per-eye complex scenes, depth
  maps, real-time reprojection, head tracking, lens distortion, large 3D
  scenes, compute shaders, AI depth, multi-pass rendering.

## If/when implemented

- Implement `RenderBackend` (see `render/api/RenderBackend.kt`) as
  `VulkanRenderBackend` — callers and `RenderPipeline` stay unchanged.
- Backend interface must remain free of backend-specific types (no VkInstance/
  VkSwapchain leaking into `render/api`).

## Never

- Do not introduce Vulkan "because it is theoretically faster".
- Do not add Vulkan while the current workload (1–few textures, simple shader,
  60fps) meets budget with OpenGL.
