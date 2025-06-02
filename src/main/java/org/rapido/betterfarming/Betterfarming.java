package org.rapido.betterfarming;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Betterfarming implements ModInitializer {
    public static final String MOD_ID = "betterfarming";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initialisation de Better Farming...");

    }
}