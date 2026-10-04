package org.qa602906.bedrockminerMaster.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

@Environment(EnvType.CLIENT)
public class BedrockminerMasterClient implements ClientModInitializer {

    public static KeyMapping toggleMiner;

    @Override
    public void onInitializeClient() {
        toggleMiner = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bedrockminer-master.toggle_miner",
                InputConstants.Type.KEYBOARD,
                66,
                new KeyMapping.Category(Identifier.fromNamespaceAndPath("bedrockminer-master", "bedrockminer"))));
    }
}