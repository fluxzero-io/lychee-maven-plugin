File argsFile = new File(basedir, "lychee-args.txt")
assert argsFile.isFile()
String out = argsFile.getText("UTF-8")
assert out.contains("--no-progress")
assert out.contains("--verbose")
assert out.contains("docs/good.md")
assert out.contains("guide/index.adoc")
assert !out.contains("docs/skip.md")
return true
