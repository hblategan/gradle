/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.plugin.devel.tasks

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import spock.lang.Unroll

class ValidateTaskPropertiesIntegrationTest extends AbstractIntegrationSpec {

    def setup() {
        buildFile << """
            apply plugin: "java-gradle-plugin"

            dependencies {
                compile gradleApi()
            }

            validateTaskProperties {
                failOnWarning = true
            }
        """
    }

    def "detects missing annotations on Java properties"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;

            public class MyTask extends DefaultTask {
                // Should be ignored because it's not a getter
                public void getVoid() {
                }

                // Should be ignored because it's not a getter
                public int getWithParameter(int count) {
                    return count;
                }

                public long getter() {
                    return 0L;
                }

                // Ignored because static
                public static int getStatic() {
                    return 0;
                }

                // Ignored because injected
                @javax.inject.Inject
                public org.gradle.api.internal.file.FileResolver getInjected() {
                    throw new UnsupportedOperationException();
                }

                // Valid because it is annotated
                @Input
                public long getGoodTime() {
                    return 0;
                }

                // Valid because it is annotated
                @Nested
                public Options getOptions() {
                    return null;
                }

                // Invalid because it has no annotation
                public long getBadTime() {
                    return System.currentTimeMillis();
                }

                public static class Options {
                    // Valid because it is annotated
                    @Input
                    public int getGoodNested() {
                        return 1;
                    }

                    // Invalid because there is no annotation
                    public int getBadNested() {
                        return -1;
                    }
                }
            }
        """

        expect:
        fails "validateTaskProperties"
        failure.assertHasCause "Task property validation failed"
        failure.assertHasCause "Warning: Task type 'MyTask': property 'badTime' is not annotated with an input or output annotation."
        failure.assertHasCause "Warning: Task type 'MyTask': property 'options.badNested' is not annotated with an input or output annotation."
        failure.assertHasCause "Warning: Task type 'MyTask': property 'ter' is not annotated with an input or output annotation."

        file("build/reports/task-properties/report.txt").text == """
            Warning: Task type 'MyTask': property 'badTime' is not annotated with an input or output annotation.
            Warning: Task type 'MyTask': property 'options.badNested' is not annotated with an input or output annotation.
            Warning: Task type 'MyTask': property 'ter' is not annotated with an input or output annotation.
        """.stripIndent().trim()
    }

    def "detects missing annotation on Groovy properties"() {
        buildFile << """
            apply plugin: "groovy"

            dependencies {
                compile localGroovy()
            }
        """
        file("src/main/groovy/MyTask.groovy") << """
            import org.gradle.api.*
            import org.gradle.api.tasks.*

            class MyTask extends DefaultTask {
                @Input
                long goodTime

                @Nested Options options

                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver fileResolver

                long badTime

                static class Options {
                    @Input String goodNested
                    String badNested
                }
            }
        """

        expect:
        fails "validateTaskProperties"
        failure.assertHasCause "Task property validation failed"
        failure.assertHasCause "Warning: Task type 'MyTask': property 'badTime' is not annotated with an input or output annotation."
        failure.assertHasCause "Warning: Task type 'MyTask': property 'options.badNested' is not annotated with an input or output annotation."

        file("build/reports/task-properties/report.txt").text == """
            Warning: Task type 'MyTask': property 'badTime' is not annotated with an input or output annotation.
            Warning: Task type 'MyTask': property 'options.badNested' is not annotated with an input or output annotation.
        """.stripIndent().trim()
    }

    def "no problems with Copy task"() {
        file("src/main/java/MyTask.java") << """
            public class MyTask extends org.gradle.api.tasks.Copy {}
        """

        expect:
        succeeds "validateTaskProperties"
    }

    def "does not report missing properties for Provider types"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.provider.Provider;
            import org.gradle.api.provider.Property;
            
            import java.io.File;
            import java.util.concurrent.Callable;

            public class MyTask extends DefaultTask {
                private final Provider<String> text;
                private final Property<File> file;
                private final Property<Pojo> pojo;

                public MyTask() {
                    text = getProject().provider(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            return "Hello World!";
                        }
                    });
                    file = getProject().getObjects().property(File.class);
                    file.set(new File("some/dir"));
                    pojo = getProject().getObjects().property(Pojo.class);
                }

                @Input
                public String getText() {
                    return text.get();
                }

                @OutputFile
                public File getFile() {
                    return file.get();
                }

                @Nested
                public Pojo getPojo() {
                    return pojo.get();
                }
            }
        """

        file("src/main/java/Pojo.java") << """
            import org.gradle.api.tasks.Input;

            public class Pojo {
                private final Boolean enabled;
                
                public Pojo(Boolean enabled) {
                    this.enabled = enabled;
                }

                @Input
                public Boolean isEnabled() {
                    return enabled;
                }
            }
        """

        expect:
        succeeds "validateTaskProperties"

        file("build/reports/task-properties/report.txt").text == ""
    }

    def "detects problems with file inputs"() {
        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import java.io.File;

            @CacheableTask
            public class MyTask extends DefaultTask {
                @Input
                public long getGoodTime() {
                    return 0;
                }

                @Nested
                public Options getOptions() {
                    return new Options();
                }

                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver fileResolver;

                public long getBadTime() {
                    return 0;
                }

                public static class Options {
                    @Input
                    public String getGoodNested() {
                        return "good";
                    }
                    public String getBadNested(){
                        return "bad";
                    }
                }
                
                @OutputDirectory
                public File getOutputDir() {
                    return new File("outputDir");
                }
                
                @InputDirectory
                @Optional
                public File getInputDirectory() {
                    return new File("inputDir");
                }
                
                @Input
                public File getFile() {
                    return new File("some-file");
                }
                
                @TaskAction
                public void doStuff() { }
            }
        """

        when:
        fails "validateTaskProperties"

        then:
        file("build/reports/task-properties/report.txt").text == """
            Warning: Task type 'MyTask': property 'badTime' is not annotated with an input or output annotation.
            Warning: Task type 'MyTask': property 'file' has @Input annotation used on property of type java.io.File.
            Warning: Task type 'MyTask': property 'inputDirectory' is missing a @PathSensitive annotation, defaulting to PathSensitivity.ABSOLUTE.
            Warning: Task type 'MyTask': property 'options.badNested' is not annotated with an input or output annotation.
        """.stripIndent().trim()
    }

    @Requires(TestPrecondition.JDK8_OR_LATER)
    def "can validate task classes using external types"() {
        buildFile << """
            ${jcenterRepository()}

            dependencies {
                compile 'com.typesafe:config:1.3.2'
            }
        """

        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import java.io.File;
            import com.typesafe.config.Config;

            public class MyTask extends DefaultTask {
                @Input
                public long getGoodTime() {
                    return 0;
                }
                
                @Input
                public Config getConfig() { return null; } 
            }
        """

        expect:
        succeeds "validateTaskProperties"
    }

    @Requires(TestPrecondition.JDK8_OR_LATER)
    def "can validate task classes using types from other projects"() {
        settingsFile << """include 'lib'"""
        buildFile << """  
            allprojects {
                ${jcenterRepository()}
            }

            project(':lib') {
                apply plugin: 'java'

                dependencies {
                    compile 'com.typesafe:config:1.3.2'
                }
            }          

            dependencies {
                compile project(':lib')
            }
        """
        file("lib/src/main/java/MyUtil.java") << """
            import com.typesafe.config.Config;

            public class MyUtil {
                public Config getConfig() {
                    return null;
                }
            }
        """

        file("src/main/java/MyTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;

            public class MyTask extends DefaultTask {
                @Input
                public long getGoodTime() {
                    return 0;
                }
                
                @Input
                public MyUtil getUtil() { return null; } 
            }
        """

        expect:
        succeeds "validateTaskProperties"
    }

    def "can enable stricter validation"() {
        buildFile << """
            apply plugin: "groovy"

            dependencies {
                compile localGroovy()
            }
            
            validateTaskProperties.enableStricterValidation = true
        """
        file("src/main/groovy/MyTask.groovy") << """
            import org.gradle.api.*
            import org.gradle.api.tasks.*

            class MyTask extends DefaultTask {
                @InputFile
                File missingNormalization

                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver fileResolver
            }
        """

        when:
        fails "validateTaskProperties"

        then:
        file("build/reports/task-properties/report.txt").text == """
            Warning: Task type 'MyTask': property 'missingNormalization' is missing a @PathSensitive annotation, defaulting to PathSensitivity.ABSOLUTE.
        """.stripIndent().trim()
    }

    @Unroll
    def "reports deprecated #property setter"() {
        buildFile << """
            validateTaskProperties.${property} = sourceSets.main.${value}
        """

        when:
        executer.expectDeprecationWarnings(1)
        succeeds("validateTaskProperties")
        then:
        output.contains("The set${property.capitalize()}(FileCollection) method has been deprecated. This is scheduled to be removed in Gradle 6.0. Please use the get${property.capitalize()}().setFrom(FileCollection) method instead.")

        where:
        property    | value
        'classes'   | 'output.classesDirs'
        'classpath' | ' compileClasspath '
    }
}
