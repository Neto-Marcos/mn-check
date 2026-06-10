package br.com.mncheck;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BarcodeDecoder {
  public String decodeCode128(MultipartFile file) {
    try {
      BufferedImage image = ImageIO.read(file.getInputStream());
      if (image == null) throw new BarcodeDecodeException("A imagem enviada não pôde ser lida.");

      BufferedImage prepared = limitSize(image, 2400);
      for (BufferedImage candidate : candidates(prepared)) {
        String decoded = tryDecode(candidate);
        if (decoded != null) return decoded;
      }
      throw new BarcodeDecodeException(
          "Nenhum CODE 128 foi encontrado. Fotografe mais perto, com boa luz e sem cortar as barras."
      );
    } catch (IOException error) {
      throw new BarcodeDecodeException("Não foi possível processar a imagem.", error);
    }
  }

  private String tryDecode(BufferedImage image) {
    Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.CODE_128));
    hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
    try {
      BinaryBitmap bitmap = new BinaryBitmap(
          new HybridBinarizer(new BufferedImageLuminanceSource(image))
      );
      Result result = new MultiFormatReader().decode(bitmap, hints);
      return result.getText();
    } catch (NotFoundException error) {
      return null;
    }
  }

  private List<BufferedImage> candidates(BufferedImage image) {
    List<BufferedImage> candidates = new ArrayList<>();
    for (int turns = 0; turns < 4; turns++) {
      BufferedImage rotated = rotate(image, turns);
      candidates.add(rotated);
      candidates.addAll(horizontalBands(rotated));
    }
    return candidates;
  }

  private List<BufferedImage> horizontalBands(BufferedImage image) {
    List<BufferedImage> bands = new ArrayList<>();
    int width = image.getWidth();
    int height = image.getHeight();
    if (width < 160 || height < 100) return bands;

    double[][] ranges = {
        {0.08, 0.92},
        {0.18, 0.82},
        {0.28, 0.72},
        {0.00, 0.55},
        {0.45, 1.00}
    };
    for (double[] range : ranges) {
      int y = Math.max(0, (int) Math.round(height * range[0]));
      int bandHeight = Math.min(height - y, (int) Math.round(height * (range[1] - range[0])));
      if (bandHeight > 40) bands.add(image.getSubimage(0, y, width, bandHeight));
    }
    return bands;
  }

  private BufferedImage limitSize(BufferedImage image, int maxDimension) {
    int largest = Math.max(image.getWidth(), image.getHeight());
    if (largest <= maxDimension) return image;
    double scale = (double) maxDimension / largest;
    int targetWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
    int targetHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
    BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = resized.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
    graphics.dispose();
    return resized;
  }

  private BufferedImage rotate(BufferedImage image, int quarterTurns) {
    if (quarterTurns == 0) return image;
    int sourceWidth = image.getWidth();
    int sourceHeight = image.getHeight();
    boolean swap = quarterTurns % 2 != 0;
    BufferedImage rotated = new BufferedImage(
        swap ? sourceHeight : sourceWidth,
        swap ? sourceWidth : sourceHeight,
        BufferedImage.TYPE_INT_RGB
    );
    Graphics2D graphics = rotated.createGraphics();
    switch (quarterTurns) {
      case 1 -> {
        graphics.translate(sourceHeight, 0);
        graphics.rotate(Math.PI / 2);
      }
      case 2 -> {
        graphics.translate(sourceWidth, sourceHeight);
        graphics.rotate(Math.PI);
      }
      case 3 -> {
        graphics.translate(0, sourceWidth);
        graphics.rotate(-Math.PI / 2);
      }
      default -> {}
    }
    graphics.drawImage(image, 0, 0, null);
    graphics.dispose();
    return rotated;
  }

  public static final class BarcodeDecodeException extends RuntimeException {
    BarcodeDecodeException(String message) {
      super(message);
    }

    BarcodeDecodeException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
