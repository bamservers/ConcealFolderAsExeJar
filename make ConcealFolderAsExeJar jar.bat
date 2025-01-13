@REM Set global variables
SET CURRENT_DATE = %date% - %time%

@REM Write the manifest file
echo Main-Class: com.xophcorp.conceal.ConcealFolderAsExeJar>> manifest.txt

@REM [Class Path]
@REM > OpenGL libraries (example)
@REM echo Class-Path: lib\gluegen-rt.jar >> manifest.txt
@REM echo  lib\jogl.all.jar >>manifest.txt
@REM echo  lib\jogl.all-noawt.jar >>manifest.txt


echo Build-Date: %date% - %time%>> manifest.txt
echo Company: XophCorp>> manifest.txt
echo Support-Contact: al475@hotmail.com>> manifest.txt
echo Build-User: %USERNAME%>> manifest.txt
echo Build-Machine: %COMPUTERNAME%>> manifest.txt
echo Version: 0.1>> manifest.txt


@REM make the jar file, containing the classes
cd classes/
jar cvfm ../ConcealFolderAsExeJar.jar ../manifest.txt com
cd..

@REM delete manifest file.
del manifest.txt

pause