assert new File(basedir, "build.log").getText("UTF-8").contains("lychee reported broken links")
return true
