# A Maven Enforcer rule to ban duplicate classes on the classpath.

Having duplicate entries for the same classname on the classpath makes your
software less stable. Depending on the order of classpath entries, which in
some cases is not guaranteed, different classes may get loaded which could
result in unexpected behaviour.

This plugin works by chekcking all the classes available on the classpath and
if it finds more than one with the same fully qualified class name it checks
the content of the classes byte by byte. If they are different it generates an
error.

This applies to the classpath of the project that you add it to.  I have been
using this on the projects that produce a deployable and not on library type
modules. Although a case can be made for adding it those as well.

It ignores duplicate classes which conflict with JDK classes.

This is based on the enforcer plugin with the same name from the Extra Enforcer
Rules project: http://mojo.codehaus.org/extra-enforcer-rules/banDuplicateClasses.html

## TODO
- Group dependencies when generating errors and provide an option for full listing
- Allow checks for other resource types (e.g., xml, properties)
- Provide an exclusion list of regex patterns
