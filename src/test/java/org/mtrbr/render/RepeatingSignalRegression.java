package org.mtrbr.render;

import com.google.gson.*;
import org.mtrbr.logic.RepeatingSignalDisplay;
import java.nio.file.*;

/** Runs the production mapping and model parser, without a Minecraft client. */
public final class RepeatingSignalRegression {
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static void near(float actual,float expected) { check(Math.abs(actual-expected)<0.00001,actual+" != "+expected); }
    public static void main(String[] args) throws Exception {
        check(RepeatingSignalDisplay.fromAspect(0)==RepeatingSignalDisplay.OFF,"green");
        check(RepeatingSignalDisplay.fromAspect(1)==RepeatingSignalDisplay.ON,"red");
        for(Integer aspect:new Integer[]{2,3,-1,4,null}) check(RepeatingSignalDisplay.fromAspect(aspect)==RepeatingSignalDisplay.OFF_LIMITING,"limiting "+aspect);
        for(Integer aspect:new Integer[]{0,1,2,3,null}) {
            check(RepeatingSignalDisplay.forBinding(false,aspect)==RepeatingSignalDisplay.ON,"unbound defaults to on");
            check(RepeatingSignalDisplay.forBinding(true,aspect)==RepeatingSignalDisplay.fromAspect(aspect),"bound aspect unchanged");
        }
        Path root=Path.of("src/main/resources/assets/mtr_brsignal_addon/models/block");
        for(var display:RepeatingSignalDisplay.values()) {
            JsonObject model=JsonParser.parseString(Files.readString(root.resolve("banner_repeating_signal_"+display.textureName()+".json"))).getAsJsonObject();
            var surfaces=RepeatingSignalModel.parse(model);
            check(surfaces.size()==1,"Exactly one authored display layer");
            var surface=surfaces.get(0);
            check(surface.texture().endsWith("/"+display.textureName()),"Resolved per-model texture keys");
            check(surface.direction().equals("south"),"Authored front");
            float[] v=surface.vertices(),uv=surface.uv();
            near(v[0],12F/16);near(v[1],8F/16);near(v[2],10F/16+0.0005F);
            near(v[3],4F/16);near(v[7],0);near(uv[0],0);near(uv[1],0);near(uv[2],1);near(uv[3],1);
            // Change the authored rectangle, UV crop and alias: no baked screen bounds allowed.
            var element=model.getAsJsonArray("elements").get(0).getAsJsonObject();
            element.add("from",JsonParser.parseString("[2,1,3]"));element.add("to",JsonParser.parseString("[10,9,7]"));
            var face=element.getAsJsonObject("faces").getAsJsonObject("south");
            model.getAsJsonObject("textures").addProperty("test_alias",face.get("texture").getAsString());face.addProperty("texture","#test_alias");
            face.add("uv",JsonParser.parseString("[2,4,10,12]"));
            var shifted=RepeatingSignalModel.parse(model).get(0);
            near(shifted.vertices()[0],10F/16);near(shifted.vertices()[1],9F/16);near(shifted.vertices()[2],7F/16+0.0005F);
            near(shifted.uv()[0],2F/16);near(shifted.uv()[3],12F/16);
            face.addProperty("rotation",90);
            var uvRotated=RepeatingSignalModel.parse(model).get(0);
            near(uvRotated.vertices()[0],10F/16);near(uvRotated.vertices()[1],1F/16);
            face.addProperty("rotation",0);
            element.add("rotation",JsonParser.parseString("{\"angle\":-22.5,\"axis\":\"y\",\"origin\":[8,0,8]}"));
            var rotated=RepeatingSignalModel.parse(model).get(0);
            double angle=Math.toRadians(-22.5);
            near(rotated.vertices()[0],(float)((8+2*Math.cos(angle)+(-1+0.008)*Math.sin(angle))/16));
        }
        System.out.println("Repeating signal regression: mapping, all authored faces, aliases, cropped UVs and element rotation passed.");
    }
}
