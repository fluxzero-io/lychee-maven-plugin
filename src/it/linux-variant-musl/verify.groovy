File log = new File(basedir, "build.log")
assert log.isFile()
String out = log.getText("UTF-8")
assert out.contains("lychee-x86_64-unknown-linux-musl.tar.gz")
return true
