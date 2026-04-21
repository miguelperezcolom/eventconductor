package io.mateu.workflow.controlplaneservice.infra.out.playwright;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import io.mateu.workflow.controlplaneservice.application.out.ImageComparator;
import io.mateu.workflow.controlplaneservice.application.out.ImageComparisonResult;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import ru.yandex.qatools.ashot.Screenshot;
import ru.yandex.qatools.ashot.comparison.ImageDiff;
import ru.yandex.qatools.ashot.comparison.ImageDiffer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Paths;

@Service
public class PlaywrightAndAshotImageComparator implements ImageComparator {
    @SneakyThrows
    @Override
    public ImageComparisonResult compare(String key, String url1, String url2) {
        var pathStaging = Paths.get("demo/static/" + key + "-staging.png");
        var pathProduction = Paths.get("demo/static/" + key + "-production.png");
        try (Playwright playwright = Playwright.create()) {
            // Puedes usar Chromium, Firefox o Webkit
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setExecutablePath(Paths.get("/usr/bin/chromium-browser"))
                            .setHeadless(true)
            );
            //Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            // 1. Capturar URL de Staging
            page.navigate(url1, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));
            page.screenshot(new Page.ScreenshotOptions().setPath(pathStaging));

            // 2. Capturar URL de Producción
            page.navigate(url2, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));
            page.screenshot(new Page.ScreenshotOptions().setPath(pathProduction));

            System.out.println("Capturas realizadas con éxito.");
            browser.close();
        }

        // Ejemplo conceptual con AShot
        BufferedImage imageStaging = ImageIO.read(pathStaging.toFile());
        BufferedImage imageProduction = ImageIO.read(pathProduction.toFile());
        ImageDiff diff = new ImageDiffer().makeDiff(imageStaging, imageProduction);
        System.out.println("Creando imagen de diferencias...");
        File diffOutputFile;
        if (diff.hasDiff()) {
            System.out.println("¡Hay diferencias visuales!");
        } else {
            System.out.println("¡NO hay diferencias visuales!");
        }
        diffOutputFile = new File("demo/static/" + key + "-diffUrl.png");
        System.out.println("Guardando imagen de diferencias en " + diffOutputFile.getAbsolutePath());
        // Formatos comunes: "png", "jpg", "bmp"
        ImageIO.write(diff.getDiffImage(), "png", diffOutputFile);
        File markedOutputFile = new File("demo/static/" + key + "-marked.png");
        ImageIO.write(diff.getMarkedImage(), "png", markedOutputFile);
        File transparentMarkedOutputFile = new File("demo/static/" + key + "-transparent-marked.png");
        ImageIO.write(diff.getTransparentMarkedImage(), "png", transparentMarkedOutputFile);
        return new ImageComparisonResult("/static/" + markedOutputFile.getName(),
                "/static/" + transparentMarkedOutputFile.getName(),
                "/static/" + diffOutputFile.getName(),
                1);
    }
}