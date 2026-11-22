# Keep the JNI binding class — its methods are called from native code by name.
-keep class hev.htproxy.TProxyService { *; }

# SnakeYAML (used only to parse Clash-style "proxies:" YAML into generic Map/List
# structures — no custom bean classes) relies on some reflection internally.
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# org.json is provided by the Android platform itself, not bundled — nothing to keep.
# hysteria/xray run as separate native-executable processes (ProcessBuilder), not JNI,
# so R8 never touches them; the bundled .so files pass through untouched either way.
