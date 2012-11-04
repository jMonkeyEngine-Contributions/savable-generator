
jMonkeyEngine Savable Generator
===============================

A CodeGenerator plugin for Netbeans/jMonkeyEngine that will scan the fields of
a class and generate implementations of the read/write methods for Savable for
it.

Quick start
-----------

1.	Download the .nbm module 
2.	In the IDE
	1.	Tools->Plugins->Downloaded->Add Plugins... 
	2.	Select the downloaded file
	3.	Restart the IDE as necessary
	4.	Open your project
	5.	Navigate to file you want to generate Savable read/write methods in
	6.	Make sure your class, and any sub-classes
	7.	With the cursor within the target class, hit Alt-Insert (or Source->Insert code...) and select the generator from the menu

Use Cases
---------

 *	Generally you want to just save all the fields in the class and load them all again
 *	Array/ArrayList/Map collections of savables are copy/converted as necessary
 *	An ArrayList/Map with <MySavable> types will automatically also be generated as well.

FieldInfo Annotation
--------------------

To provide configuration on how the exporter operates, 
you can annotate your class fields with com.wivlaro.jme3.export.FieldInfo 
from the [jme-exporter-helpers](http://github.com/airbaggins/jme-export-helpers) project.
You can add this .jar to your project:
(https://github.com/airbaggins/jme-export-helpers/raw/master/dist/jme3-export-helpers.jar).

How Do I...
-----------

### ...load a field from old saves when I've changed the **name**?

	@FieldInfo(storageLabel="oldFieldName") 

### ...load a field from old saves when I've changed the **type**?

	Specify the typeSuffix FieldInfo parameter with the previous

	@FieldInfo(typeSuffix="Boolean")
	int usedToBeABoolean;

	@FieldInfo(typeSuffix="SavableArray")
	ArrayList<MySavable> usedToBeAnArray;

### ...use my own special setter/getter when loading/saving?

	@FieldInfo(setMethod="setLevel", getMethod="getLevel")
	int level;


###	...use a getter/setter method when loading/saving a field?
	
	Add an annotation @FieldInfo(setMethod="setMethodName", getMethod="getMethodName")

Definitions
-----------

 *	*Type Suffix* - the suffix of the jme InputCapsule's read* (and some of the OutputCapsule's write)
	methods that we can infer a Java type from.
