package com.example.simpleautofish.compat;

import com.example.simpleautofish.config.AutoFishConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return AutoFishConfigScreen::new;
	}
}
