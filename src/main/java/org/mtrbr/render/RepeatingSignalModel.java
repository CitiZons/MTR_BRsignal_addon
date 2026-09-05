package org.mtrbr.render;

import com.google.gson.*;
import java.util.*;

/** Authored JSON face geometry in block units, independent of block orientation. */
public final class RepeatingSignalModel {
    private RepeatingSignalModel() {}
    public record Surface(String texture, String direction, float[] vertices, float[] uv) {}

    public static List<Surface> parse(JsonObject model) {
        final List<Surface> result = new ArrayList<>();
        final JsonObject textures = model.getAsJsonObject("textures");
        for (JsonElement value : model.getAsJsonArray("elements")) {
            final JsonObject element = value.getAsJsonObject();
            final float[] a = vector(element.getAsJsonArray("from"));
            final float[] b = vector(element.getAsJsonArray("to"));
            for (var entry : element.getAsJsonObject("faces").entrySet()) {
                final JsonObject face = entry.getValue().getAsJsonObject();
                String texture = face.get("texture").getAsString();
                final Set<String> seen = new HashSet<>();
                while (texture.startsWith("#") && seen.add(texture)) {
                    final JsonElement alias = textures.get(texture.substring(1));
                    texture = alias == null ? "" : alias.getAsString();
                }
                if (!texture.contains(":block/repeating_signal/")) continue;
                final float x=a[0], y=a[1], z=a[2], X=b[0], Y=b[1], Z=b[2], e=0.008F;
                float[] v = switch (entry.getKey()) {
                    case "south" -> new float[]{X,Y,Z+e, x,Y,Z+e, x,y,Z+e, X,y,Z+e};
                    case "north" -> new float[]{x,Y,z-e, X,Y,z-e, X,y,z-e, x,y,z-e};
                    case "east" -> new float[]{X+e,Y,z, X+e,Y,Z, X+e,y,Z, X+e,y,z};
                    case "west" -> new float[]{x-e,Y,Z, x-e,Y,z, x-e,y,z, x-e,y,Z};
                    case "up" -> new float[]{X,Y+e,z, x,Y+e,z, x,Y+e,Z, X,Y+e,Z};
                    case "down" -> new float[]{X,y-e,Z, x,y-e,Z, x,y-e,z, X,y-e,z};
                    default -> throw new IllegalArgumentException("Unknown model face " + entry.getKey());
                };
                float[] uv = face.has("uv") ? vector(face.getAsJsonArray("uv")) : defaultUv(entry.getKey(),a,b);
                // Keep JSON texture rotation without requiring an atlas sprite.
                int turns = face.has("rotation") ? Math.floorMod(face.get("rotation").getAsInt()/90,4) : 0;
                float[] rotated = v.clone();
                for (int i=0;i<4;i++) System.arraycopy(v,Math.floorMod(i-turns,4)*3,rotated,i*3,3);
                v=rotated;
                if (element.has("rotation")) rotate(v,element.getAsJsonObject("rotation"));
                for (int i=0;i<v.length;i++) v[i]/=16;
                for (int i=0;i<uv.length;i++) uv[i]/=16;
                result.add(new Surface(texture,entry.getKey(),v,uv));
            }
        }
        return List.copyOf(result);
    }
    private static float[] vector(JsonArray a) {
        float[] result=new float[a.size()];
        for(int i=0;i<result.length;i++) result[i]=a.get(i).getAsFloat();
        return result;
    }
    private static float[] defaultUv(String face,float[] a,float[] b) {
        return switch(face) {
            case "south" -> new float[]{a[0],16-b[1],b[0],16-a[1]};
            case "north" -> new float[]{16-b[0],16-b[1],16-a[0],16-a[1]};
            case "east" -> new float[]{16-b[2],16-b[1],16-a[2],16-a[1]};
            case "west" -> new float[]{a[2],16-b[1],b[2],16-a[1]};
            case "up" -> new float[]{a[0],a[2],b[0],b[2]};
            default -> new float[]{a[0],16-b[2],b[0],16-a[2]};
        };
    }
    private static void rotate(float[] v,JsonObject rotation) {
        float[] o=vector(rotation.getAsJsonArray("origin"));
        double angle=Math.toRadians(rotation.get("angle").getAsDouble()),c=Math.cos(angle),s=Math.sin(angle);
        int axis=switch(rotation.get("axis").getAsString()) { case "x" -> 0; case "y" -> 1; default -> 2; };
        int i1=(axis+1)%3,i2=(axis+2)%3;
        double scale=rotation.has("rescale") && rotation.get("rescale").getAsBoolean() ? 1/Math.cos(angle) : 1;
        for(int i=0;i<v.length;i+=3) {
            double a=v[i+i1]-o[i1],b=v[i+i2]-o[i2];
            v[i+i1]=(float)(o[i1]+(a*c-b*s)*scale);
            v[i+i2]=(float)(o[i2]+(a*s+b*c)*scale);
        }
    }
}
