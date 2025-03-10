const path = require('path');
const CopyPlugin = require('copy-webpack-plugin');

/* module.exports = function config(env) {
    return {
        output: {
            path: path.join(__dirname, './dist/apps/mqtt-configuration/mqtt-mapping')
        },
        plugins: [
            new CopyPlugin({
                patterns: [
                    { from: 'node_modules/jsoneditor/dist/jsoneditor.min.css', to:  path.join(__dirname, './jsoneditor.min.css') },
                ],
            }),
            new CopyPlugin({
                patterns: [
                    { from: 'node_modules/jsoneditor/dist/jsoneditor.min.css', to:  path.join(__dirname, '/assets/dist/jsoneditor.min.css') },
                ],
            })
        ],
    }
}; */

module.exports = function config(env) {
    return {
        output: {
            path: path.join(__dirname, './dist/apps/mqtt-mapping')
        },
        plugins: [
            new CopyPlugin({
                patterns: [
                    { from: 'node_modules/jsoneditor/dist/img/jsoneditor-icons.svg', to:  path.join(__dirname, './dist/apps/mqtt-mapping/assets/img') },
                   // { from: 'src/shared/help', to:  path.join(__dirname, './dist/apps/mqtt-mapping/assets/help') },
                ],
            }),
        ],
    }
}; 

/* module.exports = function config(env) {
    return {
    }
}; */
//frontend/mqtt-configuration/node_modules/jsoneditor/dist/img/jsoneditor-icons.svg