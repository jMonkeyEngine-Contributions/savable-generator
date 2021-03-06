jMonkeyEngine Savable Generator
===============================

A CodeGenerator plugin for Netbeans/jMonkeyEngine that will scan the fields of
a class and generate implementations of the read/write methods for Savable for
it.

Quick start
-----------

### Installation:
1.	Tools->Plugins->Downloaded->Add Plugins... 
2.	Find savable-generator in the list
3.	Restart the IDE as necessary

### Usage
1.	Make sure your class implements Savable, but has no read/write method
2.	Place the cursor somewhere within the target class code
3.	Hit Alt-Insert (or Source->Insert code...) and select the savable generator from the menu
4.	Review generated code and save.

Use Cases
---------

 *	Generally you want to just save all the fields in the class and load them all again
 *	Array/ArrayList/Map collections of savables are copy/converted as necessary
 *	Any OtherSavable classes will have their read/write methods generated
 *	An ArrayList/Map with <MySavable> types will automatically also be generated as well.

Annotations
-----------

The generator uses annotations to configure how it generates code. 
See the next section for a couple of examples.

To use the annotations, you'll need to include this in your project: [jme-exporter-helpers](http://github.com/airbaggins/jme-export-helpers)
Here is a direct link to the latest .jar file: https://github.com/airbaggins/jme-export-helpers/raw/master/dist/jme3-export-helpers.jar

How Do I...
-----------

### ...load a field from old saves when I've changed the *name*?

	@SerializeFieldConfig(storageLabel="oldFieldName") 

### ...load a field from old saves when I've changed the *type*?
Specify the typeSuffix SerializeFieldConfig parameter with the previous

	@SerializeFieldConfig(typeSuffix="Boolean")
	int usedToBeABoolean;

	@SerializeFieldConfig(typeSuffix="SavableArray")
	ArrayList<MySavable> usedToBeAnArray;

### ...use my own special setter/getter when loading/saving?

	@SerializeFieldConfig(setMethod="setLevel", getMethod="getLevel")
	int level;

### ...prevent the generator from overwriting my read/write method now I've customized it?

	@SerializerMethodConfig(autoGenerated=false)
	public void read(...)....

Definitions
-----------

 *	*Type Suffix* - the suffix of the jme InputCapsule's read* (and some of the OutputCapsule's write)
	methods that we can infer a Java type from.


Missing Features
----------------

Where I haven't written a converter yet, the code falls back to a straight cast.
So mostly you can see quite easily where there are errors to be fixed.

-Bill
October 2012