# GeckoView đọc file cấu hình YAML (configFilePath) bằng SnakeYAML qua reflection.
# Không giữ lại → crash khi khởi động bản release (ExceptionInInitializerError trong DebugConfig).
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
-keep class org.mozilla.gecko.util.DebugConfig { *; }
-keep class org.mozilla.gecko.util.DebugConfig$* { *; }

# Giữ tên gói (SnakeYAML gọi Class.getPackage()).
-keeppackagenames org.yaml.snakeyaml.**
