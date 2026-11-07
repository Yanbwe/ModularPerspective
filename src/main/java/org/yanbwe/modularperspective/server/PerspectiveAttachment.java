package org.yanbwe.modularperspective.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import org.yanbwe.modularperspective.api.camera.CameraDirective;
import org.yanbwe.modularperspective.api.camera.PerspectiveCodecs;
import org.yanbwe.modularperspective.api.camera.PerspectivePolicy;

/**
 * 玩家的服务端相机栈（docs/design.md §6.1）。
 *
 * <p>这是唯一被持久化的状态：指令列表、策略、HUD 可见性和 revision/seq 计数。
 * 客户端输出、平滑后的相机值等派生数据都不在这里。
 */
public final class PerspectiveAttachment {
    public static final Codec<PerspectiveAttachment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PerspectiveCodecs.DIRECTIVE.listOf().optionalFieldOf("directives", List.of()).forGetter(PerspectiveAttachment::directives),
            PerspectiveCodecs.POLICY.optionalFieldOf("policy", PerspectivePolicy.DEFAULT).forGetter(PerspectiveAttachment::policy),
            Codec.BOOL.optionalFieldOf("hud_visible", true).forGetter(PerspectiveAttachment::hudVisible),
            Codec.LONG.optionalFieldOf("revision", 0L).forGetter(PerspectiveAttachment::revision),
            Codec.LONG.optionalFieldOf("next_seq", 0L).forGetter(PerspectiveAttachment::nextSeq)
    ).apply(instance, PerspectiveAttachment::fromCodec));

    private final List<CameraDirective> directives = new ArrayList<>();
    private PerspectivePolicy policy = PerspectivePolicy.DEFAULT;
    private boolean hudVisible = true;
    private long revision;
    private long nextSeq;
    private boolean dirty;

    public PerspectiveAttachment() {}

    private PerspectiveAttachment(List<CameraDirective> directives, PerspectivePolicy policy, boolean hudVisible,
                                  long revision, long nextSeq) {
        this.directives.addAll(directives);
        this.policy = policy;
        this.hudVisible = hudVisible;
        this.revision = revision;
        this.nextSeq = nextSeq;
    }

    private static PerspectiveAttachment fromCodec(List<CameraDirective> directives, PerspectivePolicy policy,
                                                   boolean hudVisible, long revision, long nextSeq) {
        return new PerspectiveAttachment(directives, policy, hudVisible, revision, nextSeq);
    }

    public List<CameraDirective> directives() {
        return directives;
    }

    public PerspectivePolicy policy() {
        return policy;
    }

    public void setPolicy(PerspectivePolicy policy) {
        this.policy = policy;
        markDirty();
    }

    public boolean hudVisible() {
        return hudVisible;
    }

    public void setHudVisible(boolean hudVisible) {
        this.hudVisible = hudVisible;
        markDirty();
    }

    public long revision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public long nextSeq() {
        return nextSeq;
    }

    public void setNextSeq(long nextSeq) {
        this.nextSeq = nextSeq;
    }

    public long allocateSeq() {
        return ++nextSeq;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }
}
