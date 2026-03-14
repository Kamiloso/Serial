package page.se3.myproject;

import page.se3.serial.SerialCompiler;

public class Main {

    // Global object that represents a compiled SRL code
    // It is immutable after creation, so it can be safely used from
    // different threads.
    private static SerialCompiler serial;

    private static void initializeSerial() {
        try {
            // 1. Initialize the compiler (without custom compressor for this example)
            serial = new SerialCompiler("""
                    RECORD User
                        LET char username[16]
                        LET int age = 18
                        ENSURE username MATCHES "^[\\\\p{L}0-9_-]*$"
                        ENSURE age >= 0 AND <= 120
                    """);

        } catch (SerialCompiler.SyntaxException e) {
            System.err.println("An error occurred (syntax): " + e.getMessage());
            e.printStackTrace();
        }
    }

    // An example record that uses Serial-Java-Lib to serialize
    // and deserialize objects.
    private record User(String username, int age) {
        private static final String RECORD_TYPE = "User";

        public byte[] serialize() {
            return serial.makeRecord(RECORD_TYPE)
                    .setString("username", username)
                    .setValue("age", age)
                    .serialize();
        }

        public static User deserialize(byte[] bytes) {
            var record = serial.makeRecord(bytes);
            if (!RECORD_TYPE.equals(record.RECORD_TYPE)) { // checks whether deserialization was unsuccessful
                record = serial.makeRecord(RECORD_TYPE);
            }

            return new User(
                    record.getString("username"),
                    record.getValue("age", Integer.class)
            );
        }
    }

    public static void main(String[] args) {
        initializeSerial();

        try {
            // Create the user record
            User user = new User("Cameleon-123", 35);
            System.out.println("Original user: " + user);

            // Serialize record
            byte[] bytes = user.serialize();
            System.out.println("Serialized to " + bytes.length + " bytes.");

            // Deserialize record
            User user2 = User.deserialize(bytes);
            System.out.println("User from bytes: " + user2);

        } catch (SerialCompiler.OperationException e) {
            System.err.println("An error occurred (operation): " + e.getMessage());
            e.printStackTrace();
        }
    }
}