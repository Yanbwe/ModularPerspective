package org.yanbwe.modularperspective.server;

import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.yanbwe.modularperspective.ModularPerspective;

/** 服务端相机栈的 attachment 注册（docs/design.md §6.1）。 */
public final class PerspectiveAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, ModularPerspective.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PerspectiveAttachment>> PERSPECTIVE =
            ATTACHMENT_TYPES.register("perspective",
                    () -> AttachmentType.builder(PerspectiveAttachment::new)
                            .serialize(PerspectiveAttachment.CODEC)
                            .copyOnDeath()
                            .build());

    private PerspectiveAttachments() {}

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }

    /** 便于其它类引用的类型安全访问器。 */
    public static Supplier<AttachmentType<PerspectiveAttachment>> type() {
        return PERSPECTIVE;
    }
}
