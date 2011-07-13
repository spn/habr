/*
 * Simple SSH deploy script exmaple.
 *
 */
includeTargets << grailsScript("Clean")
includeTargets << grailsScript("Init")
includeTargets << grailsScript("War")

@GrabResolver(name='jcraft', root='http://jsch.sourceforge.net/maven2')
@Grab(group='com.jcraft', module='jsch', version='0.1.44')
import com.jcraft.jsch.*
import grails.util.Environment
import org.codehaus.groovy.grails.commons.ConfigurationHolder


/**
 * Wrap SSH commands into closure.
 */
Session.metaClass.doRemote = { Closure closure ->
    connect()
    try {
        closure.delegate = delegate
        closure.call()
    } finally {
        disconnect()
    }
}

/**
 * Exec SSH command, throw exception on bad exit status.
 * Command output is suppressed.
 * @param session SSH session
 * @param cmd command to exec
 * @return command output string
 */
Session.metaClass.exec = { cmd ->
    Channel channel = openChannel("exec")
    channel.command = cmd
    channel.inputStream = null
    channel.errStream = System.err
    InputStream inp = channel.inputStream
    channel.connect()
    int exitStatus = -1
    StringBuilder output = new StringBuilder()
    try {
        while (true) {
            output << inp
            if (channel.closed) {
                exitStatus = channel.exitStatus
                break
            }
            try {
                sleep(1000)
            } catch (Exception ee) {
            }
        }
    } finally {
        channel.disconnect()
    }

    if (exitStatus != 0) {
        println output
        throw new RuntimeException("Command [${cmd}] returned exit-status ${exitStatus}")
    }
    
    output.toString()
}

Session.metaClass.scp = { sourceFile, dst ->
    ChannelSftp channel = (ChannelSftp) openChannel("sftp")
    channel.connect()
    println "${sourceFile.path} => ${dst}"
    try {
        channel.put(new FileInputStream(sourceFile), dst, new SftpProgressMonitor() {

            private int max = 1
            private int points = 0
            private int current = 0

            void init(int op, String src, String dest, long max) {
                this.max = max
                this.current = 0
            }

            boolean count(long count) {
                current += count
                int newPoints = (current * 20 / max) as int
                if (newPoints > points) {
                    print '.'
                }
                points = newPoints
                true
            }

            void end() {
                println ''
            }

        })
    } finally {
        channel.disconnect()
    }
}


Session.metaClass.isTomcatRunning = { tomcatHome ->
    return exec("ps -ef|grep java|grep ${tomcatHome}").contains('catalina')
}

target(main: "Deploy Grails application as WAR file.") {
    JSch jsch = new JSch()
    Properties config = new Properties()
    config.put("StrictHostKeyChecking", "no")
    config.put("HashKnownHosts",  "yes")
    jsch.config = config
    def user = 'root'
    depends(compile)
    depends(createConfig)
    def host = ConfigurationHolder.config?.deploy.host
    def username = ConfigurationHolder.config?.deploy.username
    if (!host || !username) return

    def tomcatHome = '/opt/tomcat/latest'
    def webAppPrefix = 'ROOT'
    String password = new String(System.console().readPassword("Enter password for ${username}@${host}: "))
    Session session = jsch.getSession(user, host, 22)
    session.setPassword(password)
    session.doRemote {
        if (isTomcatRunning(tomcatHome)) {
            println "Tomcat running. Stopping..."
            exec "bash --login -i -c '${tomcatHome}/bin/catalina.sh stop 100 -force'"
            // wait for being stopped.
            int seconds = 0
            while (seconds < 100) {
                sleep(1000)
                if (!isTomcatRunning(tomcatHome)) {
                    break
                }
                seconds++
            }
            if (isTomcatRunning(tomcatHome)) {
                println "Unable to stop Tomcat at ${tomcatHome}."
                return
            }

            println "Tomcat (${tomcatHome}) stopped."
        }

        // Deploy WAR
        depends(war)

        // Clear old ROOT application.
        exec "rm -f ${tomcatHome}/webapps/${webAppPrefix}.*"
        exec "rm -rf ${tomcatHome}/webapps/${webAppPrefix}"
        println "Cleaned old ${webAppPrefix} application."

        File warFile = grailsSettings.projectWarFile
        if (!warFile) {
            println "WAR ${warFile} not found."
        }

        scp warFile, "${tomcatHome}/webapps/${webAppPrefix}.war"

        exec "bash --login -i -c '${tomcatHome}/bin/catalina.sh start'"
        println "Deploy complete."
    }
}

setDefaultTarget(main)