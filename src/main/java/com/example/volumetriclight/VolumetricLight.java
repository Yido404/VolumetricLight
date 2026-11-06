package com.example.volumetriclight;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(VolumetricLight.ID)
public final class VolumetricLight {
    public static final String ID = "volumetriclight";
    public static final Logger LOG = LogUtils.getLogger();
    public VolumetricLight() {
        LightNetwork.register();
        MinecraftForge.EVENT_BUS.register(LightService.class);
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> LightCommands.register(e.getDispatcher()));
    }
}
