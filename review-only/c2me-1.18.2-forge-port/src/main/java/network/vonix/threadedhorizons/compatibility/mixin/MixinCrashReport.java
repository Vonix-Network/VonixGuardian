package network.vonix.threadedhorizons.compatibility.mixin;

import net.minecraft.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CrashReport.class)
public class MixinCrashReport {

    @Redirect(method = "getFriendlyReport", at = @At(value = "INVOKE", target = "Ljava/lang/StringBuilder;append(Ljava/lang/String;)Ljava/lang/StringBuilder;"))
    private StringBuilder redirectAppend(StringBuilder stringBuilder, String str) {
        stringBuilder.append(str);
        if (str.equals("---- Minecraft Crash Report ----\n") && !ThreadedHorizonsCompatibilityModule.getEnabledMods().isEmpty()) {
            stringBuilder.append("\n");
            stringBuilder.append("-".repeat(16)).append("\n");
            stringBuilder.append("Threaded Horizons Compatibility Module Notice: \n");
            stringBuilder.append("Contact Threaded Horizons maintainers before reporting to mod authors if you encountered issues with the following mods: \n");
            for (String mod : ThreadedHorizonsCompatibilityModule.getEnabledMods()) {
                stringBuilder.append(String.format("- %s\n", mod));
            }
            stringBuilder.append("You can try disabling compatibility modules for these mods in \"threadedhorizons-compat.toml\" and try reproduce again. \n");
            stringBuilder.append("Or try to reproduce without Threaded Horizons. \n");
            stringBuilder.append("-".repeat(16)).append("\n");
            stringBuilder.append("\n");
        }
        return stringBuilder;
    }

}
