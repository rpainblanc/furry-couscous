pipeline {
    agent {
        label 'built-in'
    }
    stages {
        stage('init') {
            steps {
                script {
                    sh 'git reset --hard && git clean -xfdf'
                    def commit_id = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    sh 'printenv | sort'
                    def jenkins_lib_groovy = load 'lib.groovy'
                    withCredentials([
                            usernamePassword(credentialsId: 'jenkins-dataiku', usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_PASSWORD')]) {
                        def tm_events = jenkins_lib_groovy.getGitHubPRIssueTimelineEvents(env.GITHUB_PASSWORD, "17873")
                        def pr_issue_comments = jenkins_lib_groovy.getGitHubPRIssueComments(env.GITHUB_PASSWORD, "17873")
                        writeJSON file: 'github-issue-events.json', json: tm_events
                        writeJSON file: 'github-issue-comments.json', json: pr_issue_comments
                        archiveArtifacts artifacts: '*.json', allowEmptyArchive: true
                    }
                }
            }
        }
    }
}
