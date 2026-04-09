package io.mateu.workflow.controlplaneservice.infra.out.r2;

import software.amazon.awssdk.transfer.s3.progress.TransferListener;

public class UploadProgressBar implements TransferListener {
    private long totalBytes = 0;

    @Override
    public void transferInitiated(TransferListener.Context.TransferInitiated context) {
        // En 2.25.x el método es totalSizeInBytes()
        this.totalBytes = context.progressSnapshot().transferredBytes();
        System.out.println("📦 Iniciando transferencia a R2...");
    }

    @Override
    public void bytesTransferred(TransferListener.Context.BytesTransferred context) {
        // El snapshot nos da los bytes ya enviados
        long transferred = context.progressSnapshot().transferredBytes();

        if (totalBytes > 0) {
            int percent = (int) ((transferred * 100) / totalBytes);
            String bar = "=".repeat(Math.max(0, percent / 2)) + " ".repeat(Math.max(0, 50 - (percent / 2)));
            System.out.print(String.format("\r[%s] %d%% (%d/%d bytes)", bar, percent, transferred, totalBytes));
        } else {
            System.out.print(String.format("\rEnviado: %d bytes", transferred));
        }
    }

    @Override
    public void transferComplete(TransferListener.Context.TransferComplete context) {
        System.out.println("\n✅ Proceso finalizado.");
    }
}