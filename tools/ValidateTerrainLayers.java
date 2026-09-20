import java.nio.*;
import java.util.*;
import me.cortex.vulkanite.compat.TerrainLayers;
import static org.lwjgl.system.MemoryUtil.*;
class ValidateTerrainLayers {
 static ByteBuffer face(boolean tint,int shift,int xOffset) {
  var b=memCalloc(256);
  int[][] points={{0,0},{100,0},{100,100},{0,100}};
  for(int i=0;i<4;i++) {int v=(i+shift)%4,p=i*64;b.putShort(p,(short)(points[v][0]+xOffset));b.putShort(p+2,(short)points[v][1]);b.putInt(p+8,tint?0xff40b060:0xffffffff);b.putInt(p+12,(tint?1000:100)+v);b.putInt(p+28,0x7f0000);}
  return b;
 }
 public static void main(String[] args) {
  for(boolean reverse:List.of(false,true)) {
   var base=face(false,0,0);var overlay=face(true,1,0);
   try {
    if(TerrainLayers.merge(reverse?List.of(overlay,base):List.of(base,overlay))!=1)throw new AssertionError("Did not merge across passes");
    if(base.remaining()!=256||overlay.remaining()!=0)throw new AssertionError("Wrong primitive count");
    for(int i=0;i<4;i++)if(base.getInt(i*64+40)!=1000+i||base.getInt(i*64+44)!=0xff40b060||base.getInt(i*64+48)!=1||base.getInt(i*64+8)!=-1)throw new AssertionError("Lost UV mapping or tinted dirt base");
   }finally {memFree(base);memFree(overlay);}
  }
  var a=face(false,0,0);var b=face(true,0,1);
  try {if(TerrainLayers.merge(List.of(a,b))!=0||a.remaining()!=256||b.remaining()!=256)throw new AssertionError("Merged distinct surfaces");}finally{memFree(a);memFree(b);}
  a=face(true,0,0);b=face(true,0,0);
  try{if(TerrainLayers.merge(List.of(a,b))!=0)throw new AssertionError("Guessed ambiguous layer order");}finally{memFree(a);memFree(b);}
  System.out.println("PASS terrain layers: cross-pass/reversed ordering, rotated vertices, base tint preserved, duplicate removal, distinct/ambiguous surfaces retained");
 }
}