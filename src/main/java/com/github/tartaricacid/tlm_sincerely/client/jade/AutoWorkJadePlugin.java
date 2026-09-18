package com.github.tartaricacid.tlm_sincerely.client.jade;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Jade plugin bridge; only loaded when Jade is installed. */
@WailaPlugin(SincerelyExtension.MOD_ID)
public final class AutoWorkJadePlugin implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(AutoWorkJadeProvider.INSTANCE, EntityMaid.class);
    }
}
