package me.cortex.vulkanite.client.rendering.reconstruction;

import java.util.regex.Pattern;

/** Explicit pack opt-in in the expanded ray-generation source, versioned separately from scene ABI. */
public record ReconstructionRequest(int version, int mode, int quality, int output, int exposureTarget) {
    public static ReconstructionRequest parse(String source) {
        String clean = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        if (!clean.contains("vulkaniteReconstructionVersion")) return null;
        int version=literal(clean,"vulkaniteReconstructionVersion");
        if (version<1 || version>2) throw new IllegalArgumentException("Unsupported reconstruction ABI");
        int mode=literal(clean,"vulkaniteReconstructionMode"), quality=literal(clean,"vulkaniteReconstructionQuality"), output=literal(clean,"vulkaniteReconstructionOutput");
        if (mode<0 || mode>2 || quality<0 || quality>5 || output<0 || output>=16)
            throw new IllegalArgumentException("Invalid reconstruction mode, quality or output");
        int exposure=clean.contains("vulkaniteReconstructionExposure")?literal(clean,"vulkaniteReconstructionExposure"):-1;
        if(exposure>=16 || exposure==output || exposure>=0 && version<2) throw new IllegalArgumentException("Invalid reconstruction exposure target");
        return new ReconstructionRequest(version,mode,quality,output,exposure);
    }
    private static int literal(String source,String name) {
        var matcher=Pattern.compile("\\bconst\\s+int\\s+"+name+"\\s*=\\s*(\\d+)\\s*;").matcher(source);
        if (!matcher.find()) throw new IllegalArgumentException(name+" must be one literal const int");
        int value=Integer.parseInt(matcher.group(1));
        if (matcher.find()) throw new IllegalArgumentException("Duplicate "+name);
        return value;
    }
}

