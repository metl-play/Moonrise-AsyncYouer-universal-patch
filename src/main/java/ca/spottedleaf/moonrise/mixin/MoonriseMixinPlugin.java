package ca.spottedleaf.moonrise.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

public final class MoonriseMixinPlugin implements IMixinConfigPlugin {

    private static final String AMENDMENTS_CLASS = "net.mehvahdjukaar.amendments.Amendments";
    private static final String EXPLOSION_MIXIN = "ca.spottedleaf.moonrise.mixin.collisions.ExplosionMixin";

    private boolean disableExplosionMixin;

    @Override
    public void onLoad(final String mixinPackage) {
        this.disableExplosionMixin = classExists(AMENDMENTS_CLASS);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (this.disableExplosionMixin && EXPLOSION_MIXIN.equals(mixinClassName)) {
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    private static boolean classExists(final String className) {
        try {
            Class.forName(className, false, MoonriseMixinPlugin.class.getClassLoader());
            return true;
        } catch (final Throwable ex) {
            return false;
        }
    }
}
