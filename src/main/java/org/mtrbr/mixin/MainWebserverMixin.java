package org.mtrbr.mixin;

import org.mtr.core.Main;
import org.mtr.core.servlet.Webserver;
import org.mtr.libraries.org.eclipse.jetty.servlet.ServletHolder;
import org.mtrbr.web.MtrbrWebServlet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Main.class)
public abstract class MainWebserverMixin {
	@Shadow(remap = false) @Final private Webserver webserver;

	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/mtr/core/servlet/Webserver;start()V", shift = At.Shift.BEFORE), remap = false)
	private void mtrbr$registerWebUi(CallbackInfo callbackInfo) {
		webserver.addServlet(new ServletHolder(new MtrbrWebServlet()), "/mtrbr/*");
	}
}
