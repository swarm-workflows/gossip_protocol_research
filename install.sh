#!/bin/bash

# Download and Extract JDK
mkdir $HOME/software/
wget https://download.java.net/java/GA/jdk9/9.0.4/binaries/openjdk-9.0.4_linux-x64_bin.tar.gz
tar -xzvf openjdk-9.0.4_linux-x64_bin.tar.gz -C $HOME/software
rm -f openjdk-9.0.4_linux-x64_bin.tar.gz

wget https://dlcdn.apache.org/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz
tar -xzvf apache-maven-3.9.6-bin.tar.gz -C $HOME/software
rm -f apache-maven-3.9.6-bin.tar.gz


echo "export JAVA_HOME=\$HOME/software/jdk-9.0.4" >> .bashrc
echo "export PATH=\$HOME/software/jdk-9.0.4/bin:\$HOME/software/apache-maven-3.9.6/bin:\$PATH" >> $HOME/.bashrc

source $HOME/.bashrc

export MAVEN_OPTS="-Dmaven.resolver.transport=wagon -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true"

mvn $MAVEN_OPTS clean install
