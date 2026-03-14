package page.se3.myproject;

import java.io.*;
import java.nio.file.*;

import page.se3.serial.SerialCompiler;
import page.se3.serial.SerialCompiler.SyntaxException;
import page.se3.serial.SerialCompiler.OperationException;
import page.se3.serial.SerialCompiler.Record;

public class Main {
    public static void printHex(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            System.out.printf("%02X%s", data[i],
                    (i + 1) % 16 == 0 ? "\n" : ((i + 1) % 4 == 0 ? "  " : " "));
        }
        System.out.println();
    }

    public static void main(String[] args) {
        try {
            SerialCompiler compiler = new SerialCompiler("""
                    STRUCT Placa
                        LET int zl
                        LET int gr
                    
                        ENSURE zl >= 0
                        ENSURE gr >= 0 AND < 100
                    
                    RECORD Czlowiek
                        LET char imie[32]
                        LET char nazwisko[32]
                    
                        DEF $name-regex = "^[\\\\p{L}]*$"
                        ENSURE imie MATCHES $name-regex
                        ENSURE nazwisko MATCHES $name-regex
                    
                    RECORD Pracownik : Czlowiek
                        LET long id
                        LET char placowka[32]
                        NEST Placa placa
                    
                        ENSURE placowka NOT EQUALS "Uniwersytet Śląski"
                    
                    """);

            // Konstrukcja
            Record placa = compiler.makeRecord("Placa")
                    .setValue("zl", 4000)
                    .setValue("gr", 500); // niezgodny z warunkiem, zostanie zresetowany przy deserializacji

            Record pracownik = compiler.makeRecord("Pracownik")
                    .setValue("id", 24L)
                    .setString("imie", "Jarosław")
                    .setString("nazwisko", "Kowalski123") // niezgodny z regexem, zostanie zresetowany przy deserializacji
                    .setString("placowka", "Politechnika Śląska")
                    .setRecord("placa", placa);

            // :: opcjonalnie, dla naprawy rekordu przed deserializacja ::
            // pracownik.checkAndRepair();

            System.out.println("\nPrzed:");
            System.out.println(pracownik.getReport());

            // Serializacja
            byte[] bytes = pracownik.serialize();

            System.out.println("\nBajty:");
            printHex(bytes);

            // Deserializacja
            Record deserialized = compiler.makeRecord(bytes);

            System.out.println("\nPo:");
            System.out.println(deserialized.getReport());

        } catch (SyntaxException | OperationException ex) {
            ex.printStackTrace();
        }
    }
}
