import java.awt.image.BufferedImage;
import java.awt.Color;
import java.io.File;
import javax.imageio.ImageIO;
new File("/fixture").mkdirs();
var image = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
var graphics = image.createGraphics();
graphics.setColor(Color.BLUE);
graphics.fillRect(0, 0, 1600, 1200);
graphics.dispose();
if (!ImageIO.write(image, "jpeg", new File("/fixture/smoke.jpg"))) throw new IllegalStateException("JPEG encoder unavailable");
/exit
