String out = new File(basedir, "build.log").getText("UTF-8")
String source = new File(basedir, "docs/readme.md").getAbsolutePath()
assert out.contains("lychee reported broken links")
assert out.contains(source + ":3:6: [ERROR] https://example.invalid/broken-link (at 3:6) | Not found")
return true
