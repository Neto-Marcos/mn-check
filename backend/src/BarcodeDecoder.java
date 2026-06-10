package br.com.mncheck;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.EnumMap;
import java.util.EnumSet;
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

      Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
      hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.CODE_128));
      hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
      hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);

      BinaryBitmap bitmap = new BinaryBitmap(
          new HybridBinarizer(new BufferedImageLuminanceSource(image))
      );
      Result result = new MultiFormatReader().decode(bitmap, hints);
      return result.getText();
    } catch (NotFoundException error) {
      throw new BarcodeDecodeException("Nenhum CODE 128 foi encontrado na imagem.");
    } catch (IOException error) {
      throw new BarcodeDecodeException("Não foi possível processar a imagem.", error);
    }
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
