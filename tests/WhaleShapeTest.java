package dev.dsh.nativeapp;
import java.util.*;

/** Continuity, geometry, original brand fidelity and reduced-motion boundaries. */
public class WhaleShapeTest {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static class Capture implements WhaleShape.Sink {
        final StringBuilder path = new StringBuilder();
        final List<float[]> contours = new ArrayList<float[]>();
        final List<Float> current = new ArrayList<Float>();
        int moves,closes;float minX=100,minY=100,maxX=-100,maxY=-100;
        void add(float... values){for(int i=0;i<values.length;i+=2){current.add(values[i]);current.add(values[i+1]);minX=Math.min(minX,values[i]);maxX=Math.max(maxX,values[i]);minY=Math.min(minY,values[i+1]);maxY=Math.max(maxY,values[i+1]);}}
        public void move(float x,float y){moves++;path.append("M ").append(x).append(' ').append(y).append(' ');add(x,y);}
        public void curve(float x,float y,float a,float b,float c,float d){path.append("C ").append(x).append(' ').append(y).append(' ').append(a).append(' ').append(b).append(' ').append(c).append(' ').append(d).append(' ');add(x,y,a,b,c,d);}
        public void close(){closes++;path.append("Z ");float[] points=new float[current.size()];for(int i=0;i<points.length;i++)points[i]=current.get(i);contours.add(points);current.clear();}
        float eyeHeight(){float low=100,high=-100;for(int i=1;i<contours.get(2).length;i+=2){low=Math.min(low,contours.get(2)[i]);high=Math.max(high,contours.get(2)[i]);}return high-low;}
    }
    static Capture draw(float phase,float blink,boolean motion){Capture c=new Capture();new WhaleShape().trace(phase,blink,motion,c);return c;}
    public static void main(String[] args){
        Capture rest=draw(0,0,false);
        if(args.length>0&&args[0].equals("--dump")){System.out.print(rest.path);return;}
        check(rest.moves==4&&rest.closes==4,"original outer silhouette and all three cutouts stay closed");
        for(int frame=0;frame<241;frame++){
            Capture c=draw(frame/240f,0,true);
            check(c.moves==4&&c.closes==4&&c.minX>=0&&c.maxX<=51&&c.minY>=5&&c.maxY<=45,"single continuous contour remains finite and inside its art bounds");
        }
        Capture first=draw(0,0,true),last=draw(1,0,true);
        check(first.path.toString().equals(last.path.toString()),"cycle boundary has no pose jump");
        Capture raised=draw(.3f,0,true),lowered=draw(.8f,0,true);
        check(!raised.path.toString().equals(lowered.path.toString()),"tail flow actually changes the full path");
        check(Arrays.equals(raised.contours.get(2),lowered.contours.get(2)),"eye and face do not slide with the tail");
        Capture blink=draw(0,1,false);
        check(Math.abs(blink.eyeHeight()/rest.eyeHeight()-.1f)<.001f,"blink narrows the original eye cutout without an overlay");
        check(draw(.7f,0,false).path.toString().equals(rest.path.toString()),"reduced motion keeps exactly the canonical silhouette");
        System.out.println("Whale shape: "+checks+" contour, cycle, bounds, blink and reduced-motion checks passed.");
    }
}
